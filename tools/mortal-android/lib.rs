// SPDX-License-Identifier: AGPL-3.0-or-later
mod algo;
mod array;
mod chi_type;
mod consts;
mod decode;
mod hand;
mod macros;
mod mjai;
mod rankings;
mod state;
mod tile;
mod vec_ops;

use anyhow::{Context, Result, ensure};
use consts::ACTION_SPACE;
use jni::JNIEnv;
use jni::objects::{JFloatArray, JObject, JString, JValue};
use jni::sys::jstring;
use mjai::{Event, EventWithCanAct};
use serde::Deserialize;
use serde_json::Value;
use state::PlayerState;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr::null_mut;

#[derive(Deserialize)]
struct Request {
    player_id: u8,
    events: Vec<EventWithCanAct>,
}

fn react(request: Request, mut score: impl FnMut(&[f32], &[bool]) -> Result<[f32; ACTION_SPACE]>) -> Result<Value> {
    ensure!(request.player_id < 4, "invalid seat");
    let mut state = PlayerState::new(request.player_id);
    let (last, preceding) = request.events.split_last().context("missing MJAI events")?;
    for event in preceding {
        state.update(&event.event)?;
    }
    let candidates = state.update(&last.event)?;
    if last.can_act == Some(false) || !candidates.can_act() {
        return Ok(serde_json::to_value(Event::None)?);
    }

    let kan = if candidates.can_ankan || candidates.can_kakan {
        let (_, action) = evaluate(&state, true, &mut score)?;
        Some(action)
    } else {
        None
    };
    let (mut q, mut action) = evaluate(&state, false, &mut score)?;
    if action == 43 && !state.rule_based_agari() {
        q[43] = f32::MIN;
        action = q.iter().enumerate().max_by(|(_, l), (_, r)| l.total_cmp(r)).unwrap().0;
    }
    let event = decode::reaction(&state, request.player_id, action, kan)?;

    let mut result = serde_json::to_value(&event)?;
    if matches!(event, Event::Reach { .. }) {
        ensure!(state.update(&event)?.can_act(), "missing riichi follow-up");
        let (_, discard) = evaluate(&state, false, &mut score)?;
        let Event::Dahai { pai, .. } = decode::reaction(&state, request.player_id, discard, None)?
        else {
            anyhow::bail!("Mortal did not choose a riichi discard");
        };
        result["pai"] = serde_json::to_value(pai)?;
    }
    Ok(result)
}

fn evaluate(
    state: &PlayerState,
    kan: bool,
    score: &mut impl FnMut(&[f32], &[bool]) -> Result<[f32; ACTION_SPACE]>,
) -> Result<([f32; ACTION_SPACE], usize)> {
    let (obs, legal) = state.encode_obs(4, kan);
    let legal = legal.as_slice().context("noncontiguous mask")?;
    let q = score(obs.as_slice().context("noncontiguous observation")?, legal)?;
    let mut action = None;
    for (index, allowed) in legal.iter().copied().enumerate() {
        if allowed {
            ensure!(q[index].is_finite(), "nonfinite score for a legal action");
            if action.is_none_or(|previous| q[index] > q[previous]) {
                action = Some(index);
            }
        } else {
            ensure!(q[index] == f32::NEG_INFINITY, "missing model mask for an illegal action");
        }
    }
    Ok((q, action.context("missing legal actions")?))
}

fn score_java(
    env: &mut JNIEnv<'_>,
    scorer: &JObject<'_>,
    observation: &[f32],
    legal: &[bool],
) -> Result<[f32; ACTION_SPACE]> {
    let values = env.new_float_array(observation.len() as i32)?;
    env.set_float_array_region(&values, 0, observation)?;
    let mask = env.new_boolean_array(legal.len() as i32)?;
    let flags: Vec<_> = legal.iter().copied().map(u8::from).collect();
    env.set_boolean_array_region(&mask, 0, &flags)?;
    let reply = JFloatArray::from(env.call_method(
        scorer, "score", "([F[Z)[F",
        &[JValue::Object(&values), JValue::Object(&mask)],
    )?.l()?);
    ensure!(env.get_array_length(&reply)? as usize == ACTION_SPACE, "unexpected score shape");
    let mut scores = [0.; ACTION_SPACE];
    env.get_float_array_region(reply, 0, &mut scores)?;
    Ok(scores)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_zheyou_majsoul_MortalRules_react(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JString<'_>,
    scorer: JObject<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring> {
        let json: String = env.get_string(&request)?.into();
        let request = serde_json::from_str(&json)?;
        let reaction = react(request, |obs, legal| score_java(&mut env, &scorer, obs, legal))?;
        Ok(env.new_string(reaction.to_string())?.into_raw())
    }));
    let error = match result {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => error.to_string(),
        Err(_) => "Mortal replay panicked".into(),
    };
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
    null_mut()
}

import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {setImmediate} from 'node:timers/promises';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';

const script = readFileSync(new URL('../app/src/main/assets/training.js', import.meta.url), 'utf8');
const concat = (...parts) => Uint8Array.from(parts.flatMap(part => Array.from(part)));
function varint(value) {
    const bytes = [];
    for (; value >= 128; value >>>= 7) bytes.push((value & 127) | 128);
    return [...bytes, value];
}
const int = (id, value) => concat(varint(id * 8), varint(value));
const bytes = (id, value) => concat(varint(id * 8 + 2), varint(value.length), value);
const text = (id, value) => bytes(id, new TextEncoder().encode(value));
const wrapper = (name, data) => concat(text(1, name), bytes(2, data));
const op = (seat = 0, type = 1) => bytes(4, concat(int(1, seat), bytes(2, int(1, type)), int(5, 5000)));
const authRequest = concat([2, 1, 0], wrapper('.lq.FastTest.authGame', concat(int(1, 81), text(2, 'test-token'))));

function authResponse(count, {ai = true, seats = count === 3 ? [81, 2, 1] : [81, 2, 1, 3], error = 0} = {}) {
    const config = concat(int(1, 1), bytes(2, concat(int(1, count === 3 ? 12 : 2), int(4, Number(ai)))));
    const players = (ai ? [81] : seats).map(id => bytes(2, int(1, id)));
    const robots = ai ? seats.filter(id => id !== 81).map(id => bytes(7, int(1, id))) : [];
    return concat([3, 1, 0], wrapper('', concat(bytes(1, int(1, error)), ...players, bytes(3, seats),
        bytes(5, config), ...robots)));
}

function action(step, name, data, live = true) {
    const key = [132, 94, 78, 66, 57, 162, 31, 96, 28];
    const payload = live ? data.map((value, index) =>
        value ^ (((23 ^ data.length) + key[index % key.length] + 5 * index) & 255)) : data;
    const prototype = concat(int(1, step), text(2, name), bytes(3, payload));
    return live ? concat([1], wrapper('.lq.ActionPrototype', prototype)) : prototype;
}

function opening(count, seat = 0, dealer = seat, honba = 0) {
    const hand = count === 3
        ? '1p 3p 8p 2s 2s 4s 0s 6s 7s 7s 1z 2z 6z 4z'
        : '2m 3m 4m 5p 6p 7p 2s 3s 4s 7s 8s 1z 1z 1p';
    const tiles = hand.split(' ').slice(0, dealer === seat ? 14 : 13);
    const operation = dealer === seat ? bytes(7, concat(int(1, seat), bytes(2, int(1, 1)), int(5, 5000))) : [];
    return concat(int(2, dealer), int(3, honba), ...tiles.map(pai => text(4, pai)),
        bytes(6, concat(...Array.from({length: count}, () => varint(count === 3 ? 35000 : 25000)))),
        operation, text(14, '1m'));
}

function webView() {
    class Socket extends EventTarget {
        send() {}
        receive(data) { this.dispatchEvent(new MessageEvent('message', {data})); }
    }
    const messages = [];
    const context = {WebSocket: Socket, Uint8Array, ArrayBuffer, Blob, TextDecoder,
        ZheyouTraining: {postMessage: value => messages.push(JSON.parse(value))}};
    context.self = context.top = context;
    runInNewContext(script, context);
    return {socket: new context.WebSocket(), messages};
}

test('three- and four-player simulated WebView openings use the Windows model contract', async () => {
    for (const [count, model, pai] of [[3, 'epsilon-prism-3p', 'N'], [4, 'mortal-s42', '1p']]) {
        const {socket, messages} = webView();
        socket.send(authRequest);
        socket.receive(new Blob([authResponse(count)]));
        socket.receive(action(4, 'ActionNewRound', opening(count)).buffer);
        await setImmediate();
        const {request} = messages.at(-1);
        assert.equal(request.model, model);
        assert.equal(request.events[1].tehais.length, count);
        assert.equal(request.events[1].tehais[0].length, 13);
        assert.deepEqual(request.events.at(-1), {type: 'tsumo', actor: 0, pai});
        assert.doesNotMatch(JSON.stringify(messages), /test-token/);

        if (process.env.ZHEYOU_VERIFY_WINDOWS === '1') {
            const response = await fetch('http://127.0.0.1:25034/v3/react', {
                method: 'POST',
                headers: {Authorization: 'Bearer zheyou-local-inference', 'Content-Type': 'application/json'},
                body: JSON.stringify(request),
            });
            const result = await response.json();
            assert.equal(response.status, 200, JSON.stringify(result));
            assert.equal(result.model, model);
            assert.ok(result.reaction.type);
            assert.ok(result.candidates.length > 0 && result.candidates.length <= 3);
            console.log(model, result.reaction.type, result.reaction.pai ?? '');
        }
    }
});

test('three- and four-player tables use the signed-in seat without checking game mode', async () => {
    for (const [count, seats, model] of [
        [3, [11, 81, 12], 'epsilon-prism-3p'],
        [4, [11, 81, 12, 13], 'mortal-s42'],
    ]) {
        const {socket, messages} = webView();
        socket.send(authRequest);
        socket.receive(authResponse(count, {ai: false, seats}).buffer);
        socket.receive(action(1, 'ActionNewRound', opening(count, 1)).buffer);
        await setImmediate();
        const {type, request} = messages.at(-1);
        assert.equal(type, 'decision');
        assert.equal(request.player_id, 1);
        assert.equal(request.model, model);
        assert.equal(request.events[1].tehais[1].length, 13);
        assert.ok(request.events[1].tehais[0].every(pai => pai === '?'));
        socket.receive(action(2, 'ActionDiscardTile', concat(int(1, 1), text(2, '1p'), op(0))).buffer);
        await setImmediate();
        assert.equal(messages.at(-1).type, 'watching');
        assert.ok(!messages.at(-1).request);
    }
});

test('a valid and uniquely identified player seat is still required', async () => {
    for (const options of [{seats: [9, 2, 1, 3]}, {seats: [81, 81, 1, 3]}, {error: 1}]) {
        const {socket, messages} = webView();
        socket.send(authRequest);
        socket.receive(authResponse(4, options).buffer);
        socket.receive(action(1, 'ActionNewRound', opening(4)).buffer);
        await setImmediate();
        assert.equal(messages.at(-1).type, 'idle');
        assert.ok(messages.every(message => !message.request));
    }
});

test('reconnect rebuilds the current round, ignores duplicate steps and expires old recommendations', async () => {
    const {socket, messages} = webView();
    socket.send(authRequest);
    socket.receive(authResponse(3).buffer);
    socket.send(concat([2, 3, 0], wrapper('.lq.FastTest.syncGame', [])));
    const discard = concat(text(2, '4z'), int(5, 1));
    const deal = concat(text(2, '3z'), op());
    const restore = concat(bytes(2, action(5, 'ActionNewRound', opening(3), false)),
        bytes(2, action(6, 'ActionDiscardTile', discard, false)),
        bytes(2, action(7, 'ActionDealTile', deal, false)), int(3, 1000));
    socket.receive(concat([3, 3, 0], wrapper('', bytes(4, restore))).buffer);
    socket.receive(action(7, 'ActionDealTile', deal).buffer);
    await setImmediate();
    assert.equal(messages.filter(message => message.type === 'decision').length, 1);
    assert.equal(messages.at(-1).remainingMs, 4000);
    assert.deepEqual(messages.at(-1).request.events.slice(-2).map(event => event.type), ['dahai', 'tsumo']);
    socket.send(concat([2, 4, 0], wrapper('.lq.FastTest.inputOperation', [])));
    await setImmediate();
    assert.equal(messages.at(-1).type, 'watching');
    socket.receive(action(10, 'ActionDiscardTile', discard).buffer);
    await setImmediate();
    assert.equal(messages.at(-1).type, 'gap');
});

test('the step counter restarts for each hand on the same connection', async () => {
    for (const count of [3, 4]) {
        const {socket, messages} = webView();
        socket.send(authRequest);
        socket.receive(authResponse(count).buffer);
        socket.receive(action(0, 'ActionMJStart', []).buffer);
        socket.receive(action(1, 'ActionNewRound', opening(count)).buffer);
        socket.receive(action(2, 'ActionHule', []).buffer);
        await setImmediate();
        assert.equal(messages.at(-1).type, 'watching');

        const nextHand = action(0, 'ActionNewRound', opening(count, 0, 0, 1));
        socket.receive(nextHand.buffer);
        socket.receive(nextHand.buffer);
        await setImmediate();
        const second = messages.filter(message => message.type === 'decision');
        assert.equal(second.length, 2);
        assert.equal(second[1].request.events[1].honba, 1);
        assert.equal(second[1].request.events.length, 3);

        socket.receive(action(1, 'ActionNoTile', []).buffer);
        socket.receive(action(1, 'ActionNoTile', []).buffer);
        const dealer = count - 1;
        socket.receive(action(0, 'ActionNewRound', opening(count, 0, dealer)).buffer);
        socket.receive(action(1, 'ActionDiscardTile', concat(int(1, dealer), text(2, '1p'))).buffer);
        socket.receive(action(2, 'ActionDealTile', concat(text(2, '9p'), op())).buffer);
        await setImmediate();
        assert.equal(messages.at(-1).type, 'decision');
        const {events} = messages.at(-1).request;
        assert.equal(events[1].oya, dealer);
        assert.deepEqual(events.at(-1), {type: 'tsumo', actor: 0, pai: '9p'});
    }
});

test('sanma retains concealed-kan dora order and emits nuki', async () => {
    const {socket, messages} = webView();
    socket.send(authRequest);
    socket.receive(authResponse(3).buffer);
    socket.receive(action(1, 'ActionNewRound', opening(3)).buffer);
    socket.receive(action(2, 'ActionBaBei', []).buffer);
    const kan = concat(int(2, 3), text(3, '5s'));
    socket.receive(action(3, 'ActionAnGangAddGang', kan).buffer);
    socket.receive(action(4, 'ActionDealTile', concat(text(2, '9p'), text(6, '1m'), text(6, '3z'), op())).buffer);
    await setImmediate();
    const events = messages.at(-1).request.events.slice(-4);
    assert.deepEqual(events.map(event => event.type), ['kita', 'ankan', 'dora', 'tsumo']);
    assert.equal(events[2].dora_marker, 'W');
    assert.deepEqual(events[1].consumed, ['5sr', '5s', '5s', '5s']);
});

test('open kan dora is deferred through the replacement draw and applied before the discard', async () => {
    const {socket, messages} = webView();
    socket.send(authRequest);
    socket.receive(authResponse(4).buffer);
    socket.receive(action(1, 'ActionNewRound', opening(4)).buffer);
    socket.receive(action(2, 'ActionDiscardTile', concat(text(2, '5p'), int(1, 2))).buffer);
    const kan = concat(int(2, 2), ...['5p', '5p', '0p', '5p'].map(pai => text(3, pai)),
        bytes(4, [2, 0, 0, 0]));
    socket.receive(action(3, 'ActionChiPengGang', kan).buffer);
    socket.receive(action(4, 'ActionDealTile', concat(text(2, '9p'), text(6, '1m'), text(6, '4z'), op())).buffer);
    await setImmediate();
    const before = messages.at(-1).request.events;
    assert.deepEqual(before.slice(-2).map(event => event.type), ['daiminkan', 'tsumo']);
    assert.deepEqual(before.at(-2).consumed, ['5p', '5pr', '5p']);
    socket.receive(action(5, 'ActionDiscardTile', text(2, '9p')).buffer);
    socket.receive(action(6, 'ActionDealTile', concat(text(2, '2z'), op())).buffer);
    await setImmediate();
    assert.deepEqual(messages.at(-1).request.events.slice(-3).map(event => event.type), ['dora', 'dahai', 'tsumo']);
});

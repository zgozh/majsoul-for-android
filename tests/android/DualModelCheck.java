package com.zheyou.majsoul.dualcheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import com.zheyou.majsoul.MortalScorer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Predicate;

public final class DualModelCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            var context = getTargetContext();
            var loader = context.getClassLoader();
            var rules = loader.loadClass("com.zheyou.majsoul.MortalRules");
            var scorer = loader.loadClass("com.zheyou.majsoul.MortalScorer");
            var encode = rules.getMethod("react", String.class, scorer);
            var rulesObject = rules.getField("INSTANCE").get(null);
            var modelType = loader.loadClass("com.zheyou.majsoul.LocalModels");
            var route = modelType.getMethod("react", JSONObject.class);
            var cases = readCases(getContext());
            verifyDefensiveDetails(loader, cases.getJSONObject(0).getJSONObject("request"));
            int encoded = 0;
            try (var models = (AutoCloseable) modelType.getConstructor(Context.class).newInstance(context)) {
                for (int i = 0; i < cases.length(); i++) {
                    var fixture = cases.getJSONObject(i);
                    var label = fixture.getString("label");
                    var request = fixture.getJSONObject("request");
                    var expected = fixture.getJSONObject("reaction");
                    var calls = fixture.getJSONArray("calls");
                    int[] index = {0};
                    MortalScorer callback = (observation, legal) -> {
                        try {
                            return referenceScores(label, observation, legal, calls.getJSONObject(index[0]++));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    };
                    var decoded = new JSONObject((String) encode.invoke(rulesObject, request.toString(), callback));
                    require(index[0] == calls.length(), label + " used a different number of tensors");
                    sameAction(expected, decoded, label + " native decode");
                    var actual = (JSONObject) route.invoke(models, request);
                    sameAction(expected, actual, label + " Android ONNX");
                    encoded += index[0];
                }
                var epsilonRequest = sanma();
                var north = new JSONObject("{\"type\":\"kita\",\"actor\":0,\"pai\":\"N\"}");
                sameAction(north, (JSONObject) route.invoke(models, epsilonRequest), "Android Epsilon");
            }
            var intent = new Intent().setClassName(context, "com.zheyou.majsoul.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            var preferences = context.getSharedPreferences("prompt_position", Context.MODE_PRIVATE);
            float originalX = preferences.getFloat("x_fraction", Float.NaN);
            float originalY = preferences.getFloat("y_fraction", Float.NaN);
            boolean savedMode = preferences.contains("explanation_expanded");
            boolean originalMode = preferences.getBoolean("explanation_expanded", false);
            try {
                preferences.edit().remove("x_fraction").remove("y_fraction").remove("explanation_expanded").commit();
                Activity activity = startActivitySync(intent);
                try {
                    var webView = stopWebView(activity);
                    require(activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                            "The app does not request true landscape");
                    waitForIdleSync();
                    require(activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                                    && webView.getWidth() > webView.getHeight(),
                            "The app or WebView opened in a portrait viewport");
                    var container = (View) webView.getParent();
                    require(webView.getWidth() == container.getWidth() && webView.getHeight() == container.getHeight(),
                            "The WebView does not fill the Android content window");
                    var status = verifyPrompt(activity, sanma(), prompt -> prompt.startsWith("拔北"));
                    require(status.getParent() == container && status.getRotation() == webView.getRotation(),
                            "The WebView and overlay are not aligned");
                    require(!status.getText().toString().contains("向听"), "The prompt opened expanded");
                    float[] position = verifyPromptDrag(status);
                    int width = status.getWidth();
                    var yonma = cases.getJSONObject(0).getJSONObject("request");
                    verifyPrompt(activity, yonma, prompt -> (prompt.startsWith("切") || prompt.startsWith("立直"))
                            && !prompt.contains("\n"));
                    float[] refreshed = promptLocation(status);
                    require(Math.abs(refreshed[0] - position[0]) <= Math.abs(status.getWidth() - width) + 2,
                            "A new recommendation reset the dragged position");
                    require(Math.abs(refreshed[1] - position[1]) <= 2, "The prompt moved vertically after refreshing");
                    tapPrompt(status);
                    waitForPrompt(status, prompt -> prompt.contains("0 向听") && prompt.contains("六索、九索")
                            && prompt.contains("8 张"));
                    verifyPrompt(activity, sanma(), prompt -> prompt.startsWith("拔北") && prompt.contains("3 向听")
                            && prompt.contains("补摸"));
                    tapPrompt(status);
                    waitForPrompt(status, prompt -> !prompt.contains("\n"));
                    verifyPrompt(activity, yonma, prompt -> (prompt.startsWith("切") || prompt.startsWith("立直"))
                            && !prompt.contains("\n"));
                    tapPrompt(status);
                    waitForPrompt(status, prompt -> prompt.contains("0 向听"));
                    verifyPromptRotation(activity, status);
                } finally {
                    runOnMainSync(activity::finish);
                }
                float savedX = preferences.getFloat("x_fraction", Float.NaN);
                float savedY = preferences.getFloat("y_fraction", Float.NaN);
                require(savedX >= 0 && savedX <= 1 && savedY >= 0 && savedY <= 1,
                        "The dragged position was not saved");
                Activity reopened = startActivitySync(intent);
                try {
                    stopWebView(reopened);
                    var status = verifyPrompt(reopened, sanma(),
                            prompt -> prompt.startsWith("拔北") && prompt.contains("3 向听"));
                    require(promptLocation(status)[0] < ((View) status.getParent()).getWidth() - status.getWidth() - 40,
                            "A new activity reset the dragged prompt to the right edge");
                } finally {
                    runOnMainSync(reopened::finish);
                }
            } finally {
                var restore = preferences.edit();
                if (Float.isNaN(originalX)) restore.remove("x_fraction");
                else restore.putFloat("x_fraction", originalX);
                if (Float.isNaN(originalY)) restore.remove("y_fraction");
                else restore.putFloat("y_fraction", originalY);
                if (savedMode) restore.putBoolean("explanation_expanded", originalMode);
                else restore.remove("explanation_expanded");
                restore.commit();
            }
            result.putString("stream", "\nPASS dual-model APK. " + cases.length() + " four-player reactions, "
                    + encoded + " exact tensors and legal masks, saved explanation mode, native landscape and prompt dragging.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static JSONArray readCases(Context context) throws Exception {
        try (var input = context.getAssets().open("mortal-reference.json")) {
            var output = new ByteArrayOutputStream();
            var buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            return new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static float[] referenceScores(String label, float[] observation, boolean[] legal, JSONObject call)
            throws Exception {
        require(observation.length == 1012 * 34, label + " has the wrong observation shape");
        require(legal.length == 46, label + " has the wrong mask shape");
        require(hash(observation).equals(call.getString("observation")), label + " observation differs from Windows");
        var mask = call.getJSONArray("legal");
        var q = call.getJSONArray("q");
        var scores = new float[46];
        for (int j = 0; j < scores.length; j++) {
            require(legal[j] == mask.getBoolean(j), label + " has a different legal mask");
            scores[j] = q.isNull(j) ? Float.NEGATIVE_INFINITY : (float) q.getDouble(j);
        }
        return scores;
    }

    private static void verifyDefensiveDetails(ClassLoader loader, JSONObject original) throws Exception {
        var request = new JSONObject(original.toString());
        var events = request.getJSONArray("events");
        var round = events.getJSONObject(1).put("oya", 3).put("kyoku", 4);
        request.put("events", new JSONArray().put(events.getJSONObject(0)).put(round)
                .put(new JSONObject("{\"type\":\"tsumo\",\"actor\":3,\"pai\":\"?\"}"))
                .put(new JSONObject("{\"type\":\"reach\",\"actor\":3}"))
                .put(new JSONObject("{\"type\":\"dahai\",\"actor\":3,\"pai\":\"1p\",\"tsumogiri\":true}"))
                .put(new JSONObject("{\"type\":\"reach_accepted\",\"actor\":3}"))
                .put(events.getJSONObject(2)));
        var details = loader.loadClass("com.zheyou.majsoul.RecommendationDetails");
        var discard = new JSONObject("{\"type\":\"dahai\",\"actor\":0,\"pai\":\"1p\"}");
        var lines = (java.util.List<?>) details.getMethod("explain", JSONObject.class, JSONObject.class)
                .invoke(details.getField("INSTANCE").get(null), request, discard);
        require(lines.contains("对已立直的上家是现物"), "Missing or incorrect safety detail " + lines);
    }

    private TextView verifyPrompt(Activity activity, JSONObject request, Predicate<String> expected) throws Exception {
        var event = new JSONObject().put("type", "decision").put("request", request);
        var receive = activity.getClass().getDeclaredMethod("onGameMessage", String.class);
        receive.setAccessible(true);
        var statusField = activity.getClass().getDeclaredField("status");
        statusField.setAccessible(true);
        var status = (TextView) statusField.get(activity);
        runOnMainSync(() -> {
            try {
                receive.invoke(activity, event.toString());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        waitForPrompt(status, expected);
        return status;
    }

    private void waitForPrompt(TextView status, Predicate<String> expected) {
        String[] prompt = {""};
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            runOnMainSync(() -> prompt[0] = status.getText().toString());
            if (expected.test(prompt[0])) {
                waitForIdleSync();
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Incorrect sidebar prompt " + prompt[0]);
    }

    private void tapPrompt(TextView status) {
        int[] location = new int[2];
        runOnMainSync(() -> {
            status.getLocationOnScreen(location);
            location[0] += status.getWidth() / 2;
            location[1] += status.getHeight() / 2;
        });
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, location[0], location[1]);
        sendTouch(down, MotionEvent.ACTION_UP, location[0], location[1]);
    }

    private float[] verifyPromptDrag(TextView status) {
        int[] location = new int[2];
        float[] position = new float[2];
        float[] move = new float[2];
        runOnMainSync(() -> {
            var parent = (View) status.getParent();
            status.getLocationOnScreen(location);
            location[0] += status.getWidth() / 2;
            location[1] += status.getHeight() / 2;
            position[0] = status.getX();
            position[1] = status.getY();
            move[0] = parent.getWidth() / 4f * (status.getX() > parent.getWidth() / 2f ? -1 : 1);
            move[1] = parent.getHeight() / 4f * (status.getY() > parent.getHeight() / 2f ? -1 : 1);
        });
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, location[0], location[1]);
        sendTouch(down, MotionEvent.ACTION_MOVE, location[0] + move[0], location[1] + move[1]);
        sendTouch(down, MotionEvent.ACTION_UP, location[0] + move[0], location[1] + move[1]);
        float[] result = new float[2];
        runOnMainSync(() -> {
            result[0] = status.getX();
            result[1] = status.getY();
        });
        require(Math.abs(result[0] - position[0]) > Math.abs(move[0]) / 2
                        && Math.abs(result[1] - position[1]) > Math.abs(move[1]) / 2,
                "Dragging did not move the recommendation");
        return result;
    }

    private void sendTouch(long down, int action, float x, float y) {
        var event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private WebView stopWebView(Activity activity) throws Exception {
        var field = activity.getClass().getDeclaredField("webView");
        field.setAccessible(true);
        var webView = (WebView) field.get(activity);
        runOnMainSync(() -> {
            webView.setWebViewClient(new WebViewClient());
            webView.stopLoading();
        });
        return webView;
    }

    private float[] promptLocation(View prompt) {
        float[] position = new float[2];
        runOnMainSync(() -> {
            position[0] = prompt.getX();
            position[1] = prompt.getY();
        });
        return position;
    }

    private void verifyPromptRotation(Activity activity, View prompt) {
        int initial = activity.getResources().getConfiguration().orientation;
        int requested = activity.getRequestedOrientation();
        boolean landscape = initial == Configuration.ORIENTATION_LANDSCAPE;
        runOnMainSync(() -> activity.setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        try {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (activity.getResources().getConfiguration().orientation == initial
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            require(activity.getResources().getConfiguration().orientation != initial, "Orientation did not change");
            waitForIdleSync();
            var container = (View) prompt.getParent();
            float[] newPosition = promptLocation(prompt);
            require(newPosition[0] >= 0 && newPosition[0] + prompt.getWidth() <= container.getWidth()
                            && newPosition[1] >= 0 && newPosition[1] + prompt.getHeight() <= container.getHeight(),
                    "The prompt left the screen after rotation");
        } finally {
            runOnMainSync(() -> activity.setRequestedOrientation(requested));
        }
    }

    private static String hash(float[] obs) throws Exception {
        var buffer = ByteBuffer.allocate(obs.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : obs) buffer.putFloat(value);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(buffer.array());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value & 255) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void sameAction(JSONObject expected, JSONObject actual, String label) {
        require(expected.length() == actual.length(), label + " " + expected + " != " + actual);
        var keys = expected.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            require(String.valueOf(expected.opt(key)).equals(String.valueOf(actual.opt(key))),
                    label + " " + expected + " != " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject sanma() throws Exception {
        return new JSONObject("""
            {"model":"epsilon-prism-3p","player_id":0,"events":[
              {"type":"start_game","id":0,"num_players":3},
              {"type":"start_kyoku","bakaze":"E","dora_marker":"1m","kyoku":1,"honba":0,
               "kyotaku":0,"oya":0,"scores":[35000,35000,35000],
               "tehais":[["1p","3p","8p","2s","2s","4s","5sr","6s","7s","7s","E","S","F"],[],[]]},
              {"type":"tsumo","actor":0,"pai":"N"}]}
            """);
    }
}

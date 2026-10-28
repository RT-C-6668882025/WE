package com.opc.baize.subscription;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate launcher in debug APK only. Does not modify MainActivity or its provider. */
public final class SubscriptionProbeActivity extends Activity {
    private volatile SubscriptionProbeClient client;
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private TextView output;
    private final android.os.Handler heartbeat = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean busy;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (busy) show(client.diagnostics());
            heartbeat.postDelayed(this, 500);
        }
    };
    private Spinner models;
    private List<String> catalog = new ArrayList<>();
    private volatile int generation;
    private volatile boolean destroyed;
    interface Task { String run(SubscriptionProbeClient owner) throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        client = new SubscriptionProbeClient(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Moling · 订阅诊断 v0.1.1"); title.setTextSize(23); root.addView(title);
        TextView description = new TextView(this);
        description.setText("仅 debug 包。使用当前 ChatGPT 账号授权，测试请求会使用订阅额度。令牌只在内存；退出页面或进程结束后需重新登录。此页不改变墨灵的书写或原模型接口。\n按顺序执行：登录 → 模型列表 → 文本 → 图片。每项显示结果才算通过；这不是正式 Provider。\n当前只保留一个注册；重新授权需使用原账号/工作区。切换账号需清除此验证注册（卸载测试 App），生产版后续另做多账号管理。");
        root.addView(description);
        button(root, "Continue with ChatGPT", owner -> {
            catalog = new ArrayList<>();
            int signingGeneration = generation;
            return owner.signIn(url -> runOnUiThread(() -> {
                if (destroyed || owner != client || signingGeneration != generation) return;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { owner.close(); show("无法打开系统浏览器，请取消后重试。"); }
            }));
        });
        button(root, "1 · 获取可用模型", owner -> {
            int listingGeneration = generation;
            List<String> found = owner.models();
            runOnUiThread(() -> {
                if (destroyed || owner != client || listingGeneration != generation) return;
                catalog = found;
                models.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, found));
            });
            return "模型列表请求通过：" + found.size() + " 个可显示模型。请选择模型后继续。";
        });
        models = new Spinner(this); root.addView(models);
        // Capture UI selection before entering the worker.
        Button text = new Button(this); text.setText("2 · 文本请求（使用订阅额度）"); root.addView(text); actions.add(text);
        text.setOnClickListener(v -> {
            String model = selected();
            execute(owner -> {
                String answer = owner.inference(model, null);
                return "文本请求收到 response.completed；" + ("MOLING_OK".equals(answer.trim()) ? "预期内容匹配。" : "预期内容未匹配。") + "\n模型输出：" + answer;
            });
        });
        Button image = new Button(this); image.setText("3 · 图片请求（使用订阅额度）"); root.addView(image); actions.add(image);
        image.setOnClickListener(v -> {
            String model = selected();
            execute(owner -> {
                String answer = owner.inference(model, fixture());
                return "图片请求收到 response.completed；" + ("12".equals(answer.trim()) ? "图中 7+5 的答案匹配。" : "图中答案未匹配，请检查所选模型能力。") + "\n模型输出：" + answer;
            });
        });
        button(root, "刷新令牌（接近过期时）", owner -> owner.refresh());
        button(root, "退出并撤销当前会话", owner -> owner.signOut());
        Button cancel = new Button(this); cancel.setText("取消 / 清除内存会话"); root.addView(cancel);
        cancel.setOnClickListener(v -> {
            generation++; client.close(); worker.shutdownNow(); worker = Executors.newSingleThreadExecutor();
            client = new SubscriptionProbeClient(this); catalog = new ArrayList<>();
            models.setAdapter(null); enable(true);
            show("在途操作已取消，本地内存令牌已清除；远端授权未撤销，可到 ChatGPT 设置中断开。注册与 host ID 保留。");
        });
        Button health = new Button(this); health.setText("测试浏览器到本机回调的连通性"); root.addView(health);
        health.setOnClickListener(v -> {
            String url = client.healthUrl();
            if (url == null) { show("请先点击登录。此测试仅在等待授权期间可用。"); return; }
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { show("无法打开浏览器。"); }
        });
        Button usage = new Button(this); usage.setText("Manage usage · ChatGPT 用量设置"); root.addView(usage);
        usage.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))); }
            catch (Exception e) { show("无法打开浏览器，请手动进入 ChatGPT 设置 → 用量。"); }
        });
        output = new TextView(this); output.setTextIsSelectable(true); output.setTextSize(16); root.addView(output);
        show("待验证。登录卡住时切回此页查看阶段，并点击本机回调连通测试。不要关闭原授权标签页。");
        heartbeat.post(monitor);
    }
    private String selected() { return catalog.isEmpty() || models.getSelectedItem() == null ? "" : models.getSelectedItem().toString(); }
    private void button(LinearLayout root, String label, Task task) {
        Button b = new Button(this); b.setText(label); root.addView(b); actions.add(b); b.setOnClickListener(v -> execute(task));
    }
    private void enable(boolean value) { busy = !value; for (Button b : actions) b.setEnabled(value); models.setEnabled(value); }
    private void show(String value) { output.setText(value); }
    private void execute(Task task) {
        SubscriptionProbeClient owner = client;
        owner.beginOperation();
        int attempt = ++generation; enable(false); show("验证中……如已打开授权浏览器，请完成后切回此页。");
        worker.execute(() -> {
            String result;
            if (destroyed || generation != attempt || owner != client) return;
            try { result = task.run(owner); }
            catch (SubscriptionProtocol.Failure e) {
                result = "未通过：" + e.code + (e.status == 0 ? "" : "\nHTTP " + e.status)
                    + (e.requestId.isEmpty() ? "" : "\nrequest_id: " + e.requestId)
                    + "\n订阅权限/额度错误不会自动转用其他计费接口。";
            } catch (Exception e) {
                result = "未通过：网络、响应格式或身份验证异常（" + e.getClass().getSimpleName() + "）。未保存或输出令牌，请取消后重试。";
            }
            final String done = result;
            runOnUiThread(() -> { if (!destroyed && attempt == generation) { enable(true); show(done + "\n\n" + owner.diagnostics()); } });
        });
    }
    private static String fixture() {
        Bitmap b = Bitmap.createBitmap(400, 180, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(b); c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(64);
            c.drawText("7 + 5 = ?", 40, 110, p);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
        } finally { b.recycle(); }
    }
    @Override public void onDestroy() {
        destroyed = true; heartbeat.removeCallbacksAndMessages(null); generation++; client.close(); worker.shutdownNow(); super.onDestroy();
    }
}

package com.shijie.adbkeeper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 HTTP 服务器（纯 Java，无第三方依赖）。
 *
 * 目的：让手机/电脑浏览器直接连到电视上这个 App 的 WiFi 端口，点画布就点电视。
 * 完全不依赖 ADB —— 这是老板要的「电视装一个应用，开个 WiFi 地址出来接收点击」。
 *
 * 点击注入靠无障碍服务的 dispatchGesture()（API 24+，本机 SDK 28 满足），
 * 所以不需要 root、不需要 adb。
 */
final class WebServer {

    private static final String TAG = "AdbKeeper";

    /** 端口从 8848 起试，被占用就往后挪。 */
    private static final int[] PORTS = {8848, 8849, 8850, 8851};

    private static volatile WebServer instance;

    private final Context app;
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile int actualPort = -1;
    private ServerSocket server;
    private byte[] htmlCache;

    private WebServer(Context ctx) {
        this.app = ctx;
    }

    static WebServer get(Context ctx) {
        if (instance == null) {
            synchronized (WebServer.class) {
                if (instance == null) instance = new WebServer(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    int port() {
        return actualPort;
    }

    boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    /** 幂等：已经在跑就直接返回。 */
    void start() {
        synchronized (lock) {
            if (isRunning()) return;
            for (int p : PORTS) {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(p), 8);
                    server = ss;
                    actualPort = p;
                    running = true;
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "bind " + p + " failed: " + t);
                }
            }
            if (!running) {
                Log.w(TAG, "web server could not bind any port");
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "tvweb-accept");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "web server listening on " + actualPort);
        }
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignore) {}
        server = null;
        actualPort = -1;
    }

    private void loop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
                final Socket sock = s;
                Thread h = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(sock);
                    }
                }, "tvweb-conn");
                h.setDaemon(true);
                h.start();
            } catch (Throwable t) {
                if (running) Log.w(TAG, "accept: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ 请求处理

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), "UTF-8"));

            String reqLine = in.readLine();
            if (reqLine == null) return;
            String[] head = reqLine.split(" ");
            if (head.length < 2) return;
            String method = head[0];
            String target = head[1];

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && "content-length".equalsIgnoreCase(line.substring(0, c).trim())) {
                    try {
                        contentLength = Integer.parseInt(line.substring(c + 1).trim());
                    } catch (Throwable ignore) {}
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int r = in.read(buf, off, contentLength - off);
                    if (r < 0) break;
                    off += r;
                }
                body = new String(buf, 0, off);
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            Map<String, String> params = new HashMap<String, String>();
            parseInto(params, query);
            parseInto(params, body);

            OutputStream out = sock.getOutputStream();

            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(out, 200, "text/html; charset=utf-8", html());
            } else if ("/status".equals(path)) {
                respondText(out, statusText());
            } else if ("/apps".equals(path)) {
                respond(out, 200, "application/json; charset=utf-8", apps());
            } else if ("/tap".equals(path)) {
                boolean ok = Remote.tap(app, num(params, "x"), num(params, "y"));
                respondText(out, ok ? "已点击" : "点击失败（无障碍未启用？）");
            } else if ("/long".equals(path)) {
                boolean ok = Remote.longPress(app, num(params, "x"), num(params, "y"), num(params, "ms"));
                respondText(out, ok ? "已长按" : "长按失败（无障碍未启用？）");
            } else if ("/swipe".equals(path)) {
                boolean ok = Remote.swipe(app, num(params, "x1"), num(params, "y1"),
                        num(params, "x2"), num(params, "y2"), num(params, "ms"));
                respondText(out, ok ? "已滑动" : "滑动失败（无障碍未启用？）");
            } else if ("/key".equals(path)) {
                boolean ok = Remote.globalAction(app, params.get("k"));
                respondText(out, ok ? "已发送" : "发送失败（无障碍未启用？）");
            } else if ("/open".equals(path)) {
                boolean ok = Remote.open(app, params.get("pkg"), params.get("act"));
                respondText(out, ok ? "已启动" : "启动失败");
            } else {
                respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"));
            }
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t);
        } finally {
            try {
                sock.close();
            } catch (Throwable ignore) {}
        }
    }

    private static void parseInto(Map<String, String> into, String s) {
        if (s == null || s.isEmpty()) return;
        String[] pairs = s.split("&");
        for (String kv : pairs) {
            int i = kv.indexOf('=');
            try {
                if (i < 0) {
                    into.put(URLDecoder.decode(kv, "UTF-8"), "");
                } else {
                    into.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                            URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                }
            } catch (Throwable ignore) {}
        }
    }

    private static int num(Map<String, String> p, String k) {
        try {
            return Integer.parseInt(p.get(k));
        } catch (Throwable t) {
            return -1;
        }
    }

    private void respondText(OutputStream out, String s) throws IOException {
        respond(out, 200, "text/plain; charset=utf-8", s.getBytes("UTF-8"));
    }

    private void respond(OutputStream out, int code, String ctype, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERROR") + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
    }

    // ------------------------------------------------------------------ 内容

    private byte[] html() {
        if (htmlCache != null) return htmlCache;
        try {
            InputStream is = app.getResources().openRawResource(R.raw.control);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            is.close();
            htmlCache = bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "read raw html: " + t);
            htmlCache = ("<h1>control.html 缺失</h1>").getBytes();
        }
        return htmlCache;
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("在线 · 端口 ").append(actualPort);
        sb.append(" · 点击注入 ").append(Remote.canInject() ? "可用" : "未启用");
        sb.append(" · ADB ").append(KeeperUtil.currentAdb(app) == 1 ? "开" : "关");
        return sb.toString();
    }

    private byte[] apps() {
        StringBuilder sb = new StringBuilder("[");
        try {
            PackageManager pm = app.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            List<ResolveInfo> keep = new ArrayList<ResolveInfo>();
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                if (app.getPackageName().equals(ri.activityInfo.packageName)) continue;
                keep.add(ri);
            }
            Collections.sort(keep, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    String la = String.valueOf(a.loadLabel(app.getPackageManager()));
                    String lb = String.valueOf(b.loadLabel(app.getPackageManager()));
                    return la.compareTo(lb);
                }
            });
            boolean first = true;
            for (ResolveInfo ri : keep) {
                if (!first) sb.append(',');
                first = false;
                String label = String.valueOf(ri.loadLabel(pm));
                sb.append("{\"label\":\"").append(esc(label)).append("\",");
                sb.append("\"pkg\":\"").append(esc(ri.activityInfo.packageName)).append("\",");
                sb.append("\"act\":\"").append(esc(ri.activityInfo.name)).append("\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "apps: " + t);
        }
        sb.append(']');
        return sb.toString().getBytes();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(" ");
                    else b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 网络工具

    /** 取本机在局域网里的 IPv4（优先 wlan，其次有线），拿不到就返回 127.0.0.1。 */
    static String localIp() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String wlan = null, eth = null, other = null;
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String n = ni.getName();
                    if (n != null && n.startsWith("wlan")) {
                        if (wlan == null) wlan = ip;
                    } else if (n != null && (n.startsWith("eth") || n.startsWith("usb"))) {
                        if (eth == null) eth = ip;
                    } else if (other == null) {
                        other = ip;
                    }
                }
            }
            if (wlan != null) return wlan;
            if (eth != null) return eth;
            if (other != null) return other;
        } catch (Throwable t) {
            Log.w(TAG, "localIp: " + t);
        }
        return fallback;
    }
}

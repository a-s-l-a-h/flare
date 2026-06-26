package com.example.flare_android_client.phoenix;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  PhoenixChannelClient.java  —  v0.5
 *  Phoenix Channels Protocol v2  (vsn=2.0.0)
 *  Compatible with Phoenix 1.7 / 1.8+
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  GRADLE DEPENDENCIES  (app/build.gradle)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *   implementation 'com.squareup.okhttp3:okhttp:4.12.0'
 *   // org.json is built into the Android SDK — no extra dependency needed
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  ANDROID MANIFEST
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *   <uses-permission android:name="android.permission.INTERNET" />
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  ARCHITECTURE  (read this once, it will save you hours)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *  PhoenixSocket          — manages the OkHttp WebSocket, reconnect backoff,
 *    │                       heartbeat, send buffer, message routing.
 *    │
 *    ├─ PhoenixChannel    — one per subscribed topic; manages join/leave/push,
 *    │    │                  push buffer, rejoin backoff.
 *    │    │
 *    │    └─ PhoenixPush  — one per outbound message; tracks timeout and maps
 *    │                       the server's phx_reply back to .receive() callbacks.
 *    │
 *    └─ RetryTimer        — exponential backoff helper used by Socket + Channel.
 *
 *  THREADING MODEL — CRITICAL TO UNDERSTAND
 *  ─────────────────────────────────────────
 *  A single dedicated HandlerThread ("PhxSocket") owns ALL mutable state in this
 *  library. The pattern mimics JS's single-threaded event loop, giving us the same
 *  correctness guarantees without heavy synchronized blocks.
 *
 *  Rule: every field in PhoenixSocket and PhoenixChannel is ONLY read or written
 *        while executing on the Handler thread.
 *
 *  OkHttp delivers its listener callbacks on internal OkHttp threads. We
 *  immediately post each callback to our Handler thread — that is the ONLY thing
 *  done inside an OkHttp listener.
 *
 *  Because all callbacks (onMessage, onOpen, onError, ...) fire on the Handler
 *  thread, NOT on the main (UI) thread, you MUST wrap UI updates:
 *
 *      channel.on("new_msg", (payload, ref, joinRef) -> {
 *          runOnUiThread(() -> myTextView.setText(payload.optString("body")));
 *      });
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  WIRE PROTOCOL
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *  Every message is a JSON 5-element array:
 *      [join_ref, ref, topic, event, payload]
 *
 *  join_ref  — the ref of the phx_join push that established the session.
 *              null for server broadcasts and heartbeat.
 *  ref       — unique monotone string counter (null on broadcasts).
 *  topic     — e.g. "room:lobby", "phoenix"
 *  event     — "phx_join", "phx_leave", "phx_reply", "phx_error",
 *               "phx_close", "heartbeat", or any custom event string.
 *  payload   — JSON object
 *
 *  Heartbeat: every 30s we send [null, ref, "phoenix", "heartbeat", {}].
 *  If no reply arrives within another 30s the socket is considered dead
 *  and we tear down + reconnect.
 *
 *  Reply routing: the server sends event="phx_reply" with payload={status,response}.
 *  We translate this to a synthetic local event "chan_reply_" + ref so the
 *  originating PhoenixPush can match it.
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  KEY CORRECTNESS RULES (derived from studying the JS source)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *  1.  join_ref session discrimination — every inbound lifecycle message carries
 *      the join_ref from the phx_join that opened the channel. If it doesn't match
 *      our current joinPush.ref, the message is stale (from a previous session) and
 *      must be silently dropped. This prevents phantom rejoins after reconnects.
 *
 *  2.  connectClock — an integer incremented every time we start a new WebSocket
 *      connection attempt. Each OkHttp listener captures the clock value at creation
 *      time and checks it at callback time. If they differ, the callback is stale and
 *      discarded. This prevents a race where a slow-arriving "onClosed" from a dead
 *      connection nulls out the conn field of a fresh, live connection.
 *
 *  3.  Push timeout starts IMMEDIATELY — even when the channel is not yet joined
 *      and the push sits in pushBuffer. If the channel never joins in time, the push
 *      times out correctly. startTimeout() is always called before buffering.
 *
 *  4.  closeWasClean = true suppresses auto-reconnect. Set it whenever WE initiate
 *      disconnect. The socket sets it back to false on onConnOpen().
 *
 *  5.  triggerChanError() skips channels already in ERRORED/LEAVING/CLOSED state
 *      so we don't double-schedule rejoin timers.
 *
 *  6.  leaveOpenTopic(topic) — before rejoining, scan for any other channel
 *      instance that is JOINED or JOINING on the same topic and leave it. This
 *      matches the JS client behaviour and prevents duplicate server subscriptions.
 *
 *  7.  makeRef() wraps cleanly at Integer.MAX_VALUE using AtomicInteger.updateAndGet.
 *
 *  8.  off() runs SYNCHRONOUSLY — PhoenixPush.cancelRefEvent() calls off() and must
 *      take effect immediately. If off() were posted to the handler, the binding would
 *      fire one extra time between post and execution. All binding mutations run on
 *      the handler thread without posting.
 *
 *  9.  waitForSocketClosed polling — before destroying the socket we poll up to
 *      5 times (150 ms apart) checking queueSize(), then close. This matches the JS
 *      "waitForBufferDone" pattern.
 *
 *  10. Android background/foreground — the socket tracks a "pageHidden" flag.
 *      On app pause we disconnect (battery friendly); on resume we reconnect if the
 *      last disconnect was not clean (i.e. it was caused by the OS, not by us).
 *
 * ───────────────────────────────────────────────────────────────────────────────
 *  QUICK START
 * ───────────────────────────────────────────────────────────────────────────────
 *
 *  // 1. Build & connect the socket (usually in your ViewModel or Application class)
 *  PhoenixSocket socket = new PhoenixSocket.Builder("wss://yourserver.com/socket")
 *      .param("token", userToken)
 *      .timeout(10_000)
 *      .logger((tag, msg) -> Log.d("Phoenix", "[" + tag + "] " + msg))
 *      .build();
 *
 *  socket.onOpen(()            -> Log.d(TAG, "socket opened"));
 *  socket.onClose((code, why)  -> Log.d(TAG, "socket closed: " + why));
 *  socket.onError(reason       -> Log.e(TAG, "socket error: " + reason));
 *  socket.connect();
 *
 *  // 2. Create a channel (does NOT join yet)
 *  PhoenixChannel channel = socket.channel("room:lobby", null);
 *
 *  // 3. Subscribe to events BEFORE joining, so you don't miss early messages
 *  channel.on("new_msg", (payload, ref, joinRef) -> {
 *      String body = payload.optString("body");
 *      runOnUiThread(() -> addMessage(body));
 *  });
 *
 *  // 4. Join
 *  channel.join()
 *      .receive("ok",      (p, r, jr) -> Log.d(TAG, "joined!"))
 *      .receive("error",   (p, r, jr) -> Log.e(TAG, "join rejected: " + p))
 *      .receive("timeout", (p, r, jr) -> Log.w(TAG, "join timed out"));
 *
 *  // 5. Push messages
 *  JSONObject msg = new JSONObject();
 *  try { msg.put("body", "Hello!"); } catch (Exception ignored) {}
 *
 *  channel.push("new_msg", msg)
 *      .receive("ok",      (p, r, jr) -> Log.d(TAG, "server ack"))
 *      .receive("error",   (p, r, jr) -> Log.e(TAG, "server error"))
 *      .receive("timeout", (p, r, jr) -> Log.w(TAG, "push timed out"));
 *
 *  // 6. Leave a channel when done
 *  channel.leave();
 *
 *  // 7. In Activity.onStop() or ViewModel.onCleared()
 *  socket.disconnect();   // or socket.shutdown() to also kill the OkHttp thread pool
 *
 *  // 8. Android lifecycle integration (call these from your Activity/Fragment)
 *  socket.onActivityPause();   // called in onStop()  — pauses the connection
 *  socket.onActivityResume();  // called in onStart() — reconnects if needed
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PhoenixChannelClient {


// ═══════════════════════════════════════════════════════════════════════════════
//  §1  CALLBACK INTERFACES
//
//  These are the public-facing callbacks your app code will implement.
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Callback for channel events and push replies.
     * <p>
     * payload  — the event's JSON data (never null; may be empty {}).
     * ref      — the message ref string assigned to this message by the socket.
     * joinRef  — the join_ref of the channel session this message belongs to.
     * <p>
     * THREADING: fired on the Phoenix HandlerThread. Wrap UI work in runOnUiThread().
     */
    public interface MessageCallback {
        void onMessage(JSONObject payload, String ref, String joinRef);
    }

    /**
     * Callback for socket-level or channel-level error conditions.
     * reason — a human-readable description of what went wrong.
     */
    public interface ErrorCallback {
        void onError(String reason);
    }

    /**
     * Fired each time the WebSocket successfully opens (including reconnects).
     */
    public interface OpenCallback {
        void onOpen();
    }

    /**
     * Fired when the WebSocket closes.
     * code   — WebSocket close code (1000 = normal, others = abnormal).
     * reason — server-supplied close reason string.
     */
    public interface CloseCallback {
        void onClose(int code, String reason);
    }

    /**
     * Optional custom logger. If not supplied, android.util.Log.d is used.
     * tag  — one of: "socket", "channel", "push", "transport", "heartbeat"
     * msg  — human-readable message
     */
    public interface PhoenixLogger {
        void log(String tag, String msg);
    }

    /**
     * Optional hook to decode inbound WebSocket messages before they are processed.
     * Runs on the OkHttp background network thread (perfect for heavy parsing/decompression).
     */
    public interface MessageDecoder {
        String decode(String text) throws Exception;
        String decode(okio.ByteString bytes) throws Exception;
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §2  RETRY TIMER
//
//  Direct Java port of the JS Timer class:
//      class Timer { constructor(callback, timerCalc) }
//
//  Used by PhoenixSocket for socket-level reconnect,
//  and by PhoenixChannel for channel-level rejoin.
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Exponential-backoff timer that executes its callback on a Handler thread.
     * <p>
     * Usage pattern (identical to JS):
     * RetryTimer t = new RetryTimer(handler, () -> doWork(), tries -> delays[tries]);
     * t.scheduleTimeout();   // fire #1 after delays[1]
     * t.scheduleTimeout();   // reschedule: fire #2 after delays[2]
     * t.reset();             // cancel, set tries back to 0
     */
    public static final class RetryTimer {

        /**
         * Returns the delay in milliseconds for the given attempt number (1-based).
         */
        interface Calc {
            long delayMs(int tries);
        }

        private final Runnable callback;
        private final Calc calc;
        private final Handler handler;
        private int tries = 0;
        private Runnable pending = null;   // currently scheduled runnable

        RetryTimer(Handler handler, Runnable callback, Calc calc) {
            this.handler = handler;
            this.callback = callback;
            this.calc = calc;
        }

        /**
         * Cancel any pending fire and reset the tries counter to 0.
         * Must be called on the Handler thread.
         */
        void reset() {
            tries = 0;
            cancelPending();
        }

        /**
         * Cancel any pending fire and schedule a new one with the next backoff delay.
         * Increments tries after the callback fires (not before), matching JS behaviour.
         * Must be called on the Handler thread.
         */
        void scheduleTimeout() {
            cancelPending();
            long delay = calc.delayMs(tries + 1);
            pending = () -> {
                tries++;
                pending = null;
                callback.run();
            };
            handler.postDelayed(pending, delay);
        }

        /**
         * Cancel the pending runnable if one exists.
         */
        private void cancelPending() {
            if (pending != null) {
                handler.removeCallbacks(pending);
                pending = null;
            }
        }
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §3  PROTOCOL CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Wire-protocol event name constants.
     */
    public static final class ChannelEvent {
        static final String CLOSE = "phx_close";
        static final String ERROR = "phx_error";
        static final String JOIN = "phx_join";
        static final String REPLY = "phx_reply";
        static final String LEAVE = "phx_leave";

        /**
         * Returns true if this is a Phoenix lifecycle event (not a user-defined event).
         */
        static boolean isLifecycleEvent(String event) {
            return CLOSE.equals(event) || ERROR.equals(event) ||
                    JOIN.equals(event) || REPLY.equals(event) ||
                    LEAVE.equals(event);
        }

        private ChannelEvent() {
        } // static-only class
    }

    /**
     * Channel state machine values.
     */
    enum ChannelState {
        CLOSED,     // initial state, and after leave completes
        ERRORED,    // join failed or socket dropped; rejoin timer is running
        JOINED,     // successfully joined, normal operation
        JOINING,    // join push is in-flight
        LEAVING     // leave push is in-flight
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §4  PHOENIX PUSH
//
//  Direct Java port of the JS Push class.
//
//  Lifecycle:
//    send() ──► startTimeout() ──► [server replies] ──► matchReceive() ──► cancelTimeout()
//                                ──► [timer fires]  ──► triggerTimeout() ──► matchReceive()
//
//  The key insight: startTimeout() is called IMMEDIATELY when push() is invoked
//  on a channel, even if the channel hasn't joined yet and the push is sitting
//  in pushBuffer. This means the user's timeout semantics are always honoured.
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Represents a single outbound push to the Phoenix server.
     * <p>
     * THREADING: All methods run on the Phoenix HandlerThread.
     * The push() method on PhoenixChannel is the one public entry point, and it
     * guarantees this by posting to the handler before touching push state.
     */
    public static final class PhoenixPush {

        // ── References ─────────────────────────────────────────────────────────────
        private final PhoenixChannel channel;
        private final String event;
        private final JSONObject payload;

        // ── Per-send state (reset on resend) ───────────────────────────────────────
        private String ref;             // allocated in startTimeout(); e.g. "42"
        private String refEvent;        // synthetic: "chan_reply_42"
        private JSONObject receivedResp;    // set when phx_reply arrives: {status, response}
        private boolean sent = false;
        private int bindingId = -1;  // channel binding id for the refEvent listener

        // ── Timeout ────────────────────────────────────────────────────────────────
        private final long timeoutMs;
        private Runnable timeoutRunnable;

        // ── Reply hooks ────────────────────────────────────────────────────────────
        // Parallel lists: hookStatuses.get(i) ↔ hookCallbacks.get(i)
        // Using parallel lists (not Map) preserves registration order and allows
        // multiple callbacks for the same status.
        private final List<String> hookStatuses = new ArrayList<>();
        private final List<MessageCallback> hookCallbacks = new ArrayList<>();

        // ── Constructor ─────────────────────────────────────────────────────────────

        PhoenixPush(PhoenixChannel channel, String event, JSONObject payload, long timeoutMs) {
            this.channel = channel;
            this.event = event;
            this.payload = (payload != null) ? payload : new JSONObject();
            this.timeoutMs = timeoutMs;
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API
        // ═══════════════════════════════════════════

        /**
         * Register a callback for a specific reply status.
         * <p>
         * status   — "ok", "error", or "timeout"
         * callback — receives (responsePayload, messageRef, joinRef)
         * <p>
         * If the reply has already been received before receive() is called,
         * the callback fires immediately (synchronously). This matches JS behaviour
         * where you can chain .receive() after the reply already arrived.
         *
         * @return this, for chaining
         */
        public PhoenixPush receive(String status, MessageCallback callback) {
            // Fire immediately if we already have the reply for this status
            if (receivedResp != null) {
                String received = receivedResp.optString("status", "");
                if (received.equals(status)) {
                    JSONObject response = safeGetObject(receivedResp, "response");
                    callback.onMessage(response, ref, null);
                }
            }
            hookStatuses.add(status);
            hookCallbacks.add(callback);
            return this;
        }

        // ═══════════════════════════════════════════
        //  PACKAGE-PRIVATE / INTERNAL
        // ═══════════════════════════════════════════

        /**
         * Re-send after a reset (used by join push on rejoin).
         * newTimeout is accepted for API parity with JS but we use the original
         * timeoutMs; the join push always uses the channel's current timeout.
         */
        public void trigger(String status, JSONObject response) {
            JSONObject resp = new JSONObject();
            try {
                resp.put("status", status);
                resp.put("response", response != null ? response : new JSONObject());
            } catch (Exception ignored) {
            }
            if (refEvent != null) {
                channel.triggerInternal(refEvent, resp, ref, null);
            }
        }

        void resend(long newTimeout) {
            // Reset clears the previous ref/refEvent/received state
            reset();
            send();
        }

        /**
         * Transmit the message (or queue it) and start the timeout clock.
         * <p>
         * Must be called on the Handler thread.
         */
        void send() {
            // If this push already timed out (e.g. was buffered too long), do not send.
            if (hasReceived("timeout")) {
                channel.getSocket().log("push",
                        "Skipping send for event '" + event + "' — already timed out");
                return;
            }
            startTimeout();
            sent = true;

            channel.getSocket().log("push",
                    channel.getTopic() + " " + event + " (ref=" + ref + ")");

            // Delegate to socket which handles encoding + queuing
            channel.getSocket().pushMessage(
                    channel.getTopic(), event, payload, ref, channel.joinRef()
            );
        }

        /**
         * Start the timeout clock.
         * <p>
         * CRITICAL: This is called immediately when push() is invoked on the channel,
         * even before the channel is joined. The timeout counts from the moment the
         * user called push(), not from when it gets onto the wire.
         * <p>
         * Allocates a new ref, registers the phx_reply listener on the channel,
         * and schedules the timeout runnable.
         * <p>
         * Must be called on the Handler thread.
         */
        void startTimeout() {
            // Cancel any previous timeout (handles resend case)
            cancelTimeout();

            // Allocate a unique ref for this push
            ref = channel.getSocket().makeRef();
            refEvent = PhoenixSocket.replyEventName(ref);

            // Register an internal listener on the channel for the server's reply.
            // When phx_reply arrives with our ref, PhoenixChannel routes it to
            // "chan_reply_<ref>" which fires this callback.
            bindingId = channel.addBinding(refEvent, (replyPayload, replyRef, replyJoinRef) -> {
                // We got a reply — cancel the timeout bomb and record the response
                cancelRefEvent();
                cancelTimeout();
                receivedResp = replyPayload;
                // Fire any matching .receive() hooks
                matchReceive(replyPayload);
            });

            // Schedule the timeout bomb
            final String capturedRef = ref; // capture for the lambda
            timeoutRunnable = () -> doTimeout(capturedRef);
            channel.getSocket().getHandler().postDelayed(timeoutRunnable, timeoutMs);
        }

        /**
         * Cancel the scheduled timeout runnable (does NOT touch bindingId).
         * Safe to call multiple times.
         */
        void cancelTimeout() {
            if (timeoutRunnable != null) {
                channel.getSocket().getHandler().removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
        }

        /**
         * Unregister the channel binding for our refEvent.
         * <p>
         * CRITICAL: This runs SYNCHRONOUSLY on the Handler thread.
         * It must NOT be posted. If it were posted, the binding could fire one extra
         * time in the window between the post() call and its execution, delivering
         * a stale reply to a push that has already been cleaned up.
         */
        void cancelRefEvent() {
            if (bindingId >= 0 && refEvent != null) {
                // Direct (synchronous) call — we are already on the handler thread
                channel.removeBinding(refEvent, bindingId);
                bindingId = -1;
            }
        }

        /**
         * Reset to the pre-sent state so the push can be re-sent.
         * Used by the join push before each rejoin.
         */
        void reset() {
            cancelRefEvent();
            cancelTimeout();
            ref = null;
            refEvent = null;
            receivedResp = null;
            sent = false;
        }

        // ── Private helpers ─────────────────────────────────────────────────────────

        /**
         * Called when the timeout fires.
         * Builds a synthetic "timeout" response and routes it through the channel's
         * trigger machinery so that all .receive("timeout", ...) hooks fire.
         */
        private void doTimeout(String capturedRef) {
            channel.getSocket().log("push",
                    "Timeout for event '" + event + "' on topic '" +
                            channel.getTopic() + "' (ref=" + capturedRef + ")");

            JSONObject resp = new JSONObject();
            try {
                resp.put("status", "timeout");
                resp.put("response", new JSONObject());
            } catch (Exception ignored) {
            }

            // Route through channel trigger so the binding fires matchReceive
            if (refEvent != null) {
                channel.triggerInternal(refEvent, resp, capturedRef, null);
            }
        }

        /**
         * Iterate over registered hooks and invoke any whose status matches
         * the received response's status field.
         */
        private void matchReceive(JSONObject resp) {
            if (resp == null) return;
            String status = resp.optString("status", "");
            JSONObject response = safeGetObject(resp, "response");

            for (int i = 0; i < hookStatuses.size(); i++) {
                if (hookStatuses.get(i).equals(status)) {
                    try {
                        hookCallbacks.get(i).onMessage(response, ref, null);
                    } catch (Exception ex) {
                        channel.getSocket().log("push",
                                "Exception in .receive(\"" + status + "\") callback: " + ex.getMessage());
                        Log.e("PhxPush", "receive() callback threw", ex);
                    }
                }
            }
        }

        /**
         * Returns true if we have already received a response with this status.
         */
        boolean hasReceived(String status) {
            return receivedResp != null &&
                    status.equals(receivedResp.optString("status", ""));
        }

        /**
         * Safely get a JSONObject field, returning empty object if absent or null.
         */
        private static JSONObject safeGetObject(JSONObject parent, String key) {
            JSONObject val = parent.optJSONObject(key);
            return (val != null) ? val : new JSONObject();
        }

        String getRef() {
            return ref;
        }

        String getEvent() {
            return event;
        }

        boolean isSent() {
            return sent;
        }
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §5  PHOENIX CHANNEL
//
//  Direct Java port of the JS Channel class.
//
//  State machine:
//    CLOSED ──► JOINING ──► JOINED ──► ERRORED ──► JOINING (auto-rejoin)
//                                    │
//                                    └──► LEAVING ──► CLOSED
//
//  THREADING: All methods run on the Phoenix HandlerThread.
//  push() and leave() are the only two methods that can be safely called from
//  any thread; they marshal their work onto the handler before touching state.
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * A Phoenix Channel — one logical pub/sub topic multiplexed over a shared socket.
     * <p>
     * Create via socket.channel(topic, params).
     * Join via channel.join().
     * Do NOT call join() more than once per instance — it auto-rejoins on reconnect.
     */
    public static final class PhoenixChannel {

        private static final String TAG = "PhxChannel";

        // ── State ───────────────────────────────────────────────────────────────────
        private ChannelState state = ChannelState.CLOSED;

        private final String topic;
        private final JSONObject params;
        private final PhoenixSocket socket;
        private long timeout;   // may be overridden by join(timeoutMs)

        // ── Binding registry ────────────────────────────────────────────────────────
        // Each binding is a triple: (event, id, callback).
        // The id is used to remove a specific binding without removing others for the
        // same event (e.g. a specific Push's refEvent listener).
        private final List<Binding> bindings = new ArrayList<>();
        private final AtomicInteger nextBindingId = new AtomicInteger(0);

        /**
         * Internal binding record.
         */
        static final class Binding {
            final String event;
            final int id;
            final MessageCallback callback;

            Binding(String event, int id, MessageCallback cb) {
                this.event = event;
                this.id = id;
                this.callback = cb;
            }
        }

        // ── Join / push buffer ──────────────────────────────────────────────────────
        private boolean joinedOnce = false;
        private final PhoenixPush joinPush;          // the one join push; reset and resent on rejoin
        private final List<PhoenixPush> pushBuffer = new ArrayList<>(); // pushes queued before join

        // ── Rejoin backoff timer ────────────────────────────────────────────────────
        private final RetryTimer rejoinTimer;

        // ── Socket state-change subscription ids (for cleanup) ─────────────────────
        private int socketOnErrorRef = -1;
        private int socketOnOpenRef = -1;

        // ── Constructor ─────────────────────────────────────────────────────────────

        /**
         * Package-private — create via PhoenixSocket.channel().
         * Setting up all the internal event handlers happens here.
         */
        PhoenixChannel(String topic, JSONObject params, PhoenixSocket socket) {
            this.topic = topic;
            this.params = (params != null) ? params : new JSONObject();
            this.socket = socket;
            this.timeout = socket.getTimeout();

            // The join push is created once at construction time.
            // reset() is called on it before each rejoin so it can be resent.
            joinPush = new PhoenixPush(this, ChannelEvent.JOIN, this.params, timeout);

            // Rejoin delays: [1000, 2000, 5000] ms, then 10000 ms forever.
            // Matches the JS client exactly.
            rejoinTimer = new RetryTimer(socket.getHandler(),
                    () -> {
                        if (socket.isConnected()) {
                            socket.log("channel", "Rejoin timer fired for topic: " + topic);
                            rejoin();
                        }
                    },
                    tries -> {
                        long[] delays = {1_000L, 2_000L, 5_000L};
                        return (tries <= delays.length) ? delays[tries - 1] : 10_000L;
                    }
            );

            // ── Wire up join push reply handlers ──────────────────────────────────

            joinPush.receive("ok", (payload, ref, joinRef) -> {
                socket.log("channel", "Joined topic: " + topic);
                state = ChannelState.JOINED;
                rejoinTimer.reset();
                // Flush all pushes that were buffered waiting for join.
                // Snapshot to avoid ConcurrentModificationException if a push callback
                // itself pushes more messages.
                List<PhoenixPush> toFlush = new ArrayList<>(pushBuffer);
                pushBuffer.clear();
                for (PhoenixPush p : toFlush) {
                    socket.log("channel", "Flushing buffered push: " + p.getEvent());
                    p.send();
                }
            });

            joinPush.receive("error", (payload, ref, joinRef) -> {
                socket.log("channel", "Join error on topic: " + topic +
                        " payload=" + payload);
                state = ChannelState.ERRORED;
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout();
                }
            });

            joinPush.receive("timeout", (payload, ref, joinRef) -> {
                socket.log("channel", "Join timeout on topic: " + topic +
                        " (joinRef=" + joinRef() + ")");
                // Politely inform the server we are giving up on this attempt.
                // The server may have received the join and is waiting; sending leave
                // cleans up that server-side process.
                new PhoenixPush(this, ChannelEvent.LEAVE, new JSONObject(), timeout).send();
                state = ChannelState.ERRORED;
                // Reset the join push so the next rejoin starts fresh
                joinPush.reset();
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout();
                }
            });

            // ── Built-in lifecycle event handlers ─────────────────────────────────

            // phx_close: server explicitly closed this channel
            addBinding(ChannelEvent.CLOSE, (payload, ref, joinRef) -> {
                socket.log("channel", "Received phx_close for topic: " + topic);
                rejoinTimer.reset();
                state = ChannelState.CLOSED;
                socket.removeChannel(this);
            });

            // phx_error: channel process crashed on the server
            addBinding(ChannelEvent.ERROR, (payload, ref, joinRef) -> {
                socket.log("channel", "Received phx_error for topic: " + topic);
                // If we're mid-join, clear the join push state so we can try again
                if (isJoining()) {
                    joinPush.reset();
                }
                state = ChannelState.ERRORED;
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout();
                }
            });

            // phx_reply: server's response to one of our pushes.
            // Re-route it as a synthetic local event "chan_reply_<ref>" so the
            // originating PhoenixPush can match it via its refEvent binding.
            addBinding(ChannelEvent.REPLY, (payload, ref, joinRef) -> {
                triggerInternal(PhoenixSocket.replyEventName(ref), payload, ref, joinRef);
            });

            // ── Subscribe to socket-level events ──────────────────────────────────

            // When the socket drops, reset the rejoin timer (it'll restart via triggerChanError)
            socketOnErrorRef = socket.onError(reason -> rejoinTimer.reset());

            // When the socket reconnects, reset the rejoin timer and attempt rejoin
            // if this channel is currently errored (i.e. it was mid-session when socket dropped)
            socketOnOpenRef = socket.onOpen(() -> {
                rejoinTimer.reset();
                if (isErrored()) {
                    socket.log("channel", "Socket reopened; rejoining topic: " + topic);
                    rejoin();
                }
            });
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API
        // ═══════════════════════════════════════════

        /**
         * Join the channel.
         * <p>
         * ONE-SHOT: Call this exactly once per channel instance.
         * The channel will auto-rejoin on reconnects internally — you do not need to
         * call join() again after a reconnect.
         * <p>
         * Returns the join PhoenixPush so you can chain .receive() callbacks:
         * channel.join()
         * .receive("ok",      (p, r, jr) -> ...)
         * .receive("error",   (p, r, jr) -> ...)
         * .receive("timeout", (p, r, jr) -> ...);
         *
         * @throws IllegalStateException if join() has already been called on this instance
         */
        public PhoenixPush join() {
            return join(timeout);
        }

        /**
         * Join with an explicit timeout (overrides the socket's default timeout).
         *
         * @throws IllegalStateException if join() has already been called on this instance
         */
        public PhoenixPush join(long timeoutMs) {
            if (joinedOnce) {
                throw new IllegalStateException(
                        "join() called more than once on channel for topic '" + topic + "'. " +
                                "A channel rejoins automatically after reconnect — do not call join() again."
                );
            }
            joinedOnce = true;
            timeout = timeoutMs;
            // Kick off the first join attempt
            rejoin();
            return joinPush;
        }

        /**
         * Push an event with a payload to the server.
         * <p>
         * Thread-safe: may be called from ANY thread (the work is posted to the handler).
         * <p>
         * If the channel is not yet JOINED, the push is buffered. The timeout clock
         * starts IMMEDIATELY even while buffered — if the channel never joins in time,
         * the push will time out.
         *
         * @param event   event name, e.g. "new_msg"
         * @param payload JSON payload (may be null for empty payload)
         * @return PhoenixPush for chaining .receive() callbacks
         * @throws IllegalStateException if join() has not been called yet
         */
        public PhoenixPush push(String event, JSONObject payload) {
            return push(event, payload, timeout);
        }

        /**
         * Push with an explicit per-push timeout.
         * Thread-safe: may be called from ANY thread.
         */
        public PhoenixPush push(String event, JSONObject payload, long timeoutMs) {
            if (!joinedOnce) {
                throw new IllegalStateException(
                        "push() called before join() on topic '" + topic + "'. " +
                                "Call channel.join() first."
                );
            }
            JSONObject safePayload = (payload != null) ? payload : new JSONObject();
            PhoenixPush push = new PhoenixPush(this, event, safePayload, timeoutMs);

            // Marshal to handler thread before touching pushBuffer or calling send()
            socket.getHandler().post(() -> {
                if (canPush()) {
                    // Channel is joined and socket is open — send immediately
                    push.send();
                } else {
                    // Buffer the push; startTimeout() ensures the clock runs immediately
                    socket.log("channel",
                            "Buffering push '" + event + "' for topic '" + topic +
                                    "' (channel state=" + state + ")");
                    push.startTimeout();
                    pushBuffer.add(push);
                }
            });
            return push;
        }

        /**
         * Leave the channel.
         * <p>
         * Sends phx_leave to the server. The server tears down the channel process.
         * onClose callbacks fire after the leave is acknowledged (or times out).
         * <p>
         * Thread-safe: may be called from ANY thread.
         *
         * @return PhoenixPush so you can chain .receive("ok", ...) if you want to
         * know when the leave was acknowledged.
         */
        public PhoenixPush leave() {
            return leave(timeout);
        }

        /**
         * Leave with an explicit timeout.
         * Thread-safe: may be called from ANY thread.
         */
        public PhoenixPush leave(long timeoutMs) {
            final PhoenixPush leavePush = new PhoenixPush(
                    this, ChannelEvent.LEAVE, new JSONObject(), timeoutMs);

            socket.getHandler().post(() -> {
                socket.log("channel", "Leaving topic: " + topic);
                rejoinTimer.reset();
                joinPush.cancelTimeout();
                state = ChannelState.LEAVING;

                // Both "ok" and "timeout" responses mean the leave is done from our side
                Runnable onLeave = () -> {
                    socket.log("channel", "Leave acknowledged for topic: " + topic);
                    triggerInternal(ChannelEvent.CLOSE, new JSONObject(), null, null);
                };
                leavePush.receive("ok", (p, r, jr) -> onLeave.run());
                leavePush.receive("timeout", (p, r, jr) -> onLeave.run());
                leavePush.send();

                // If we can't actually push (socket offline), resolve immediately so
                // the state machine transitions correctly to CLOSED
                if (!canPush()) {
                    socket.log("channel",
                            "Socket offline during leave — resolving immediately for topic: " + topic);
                    leavePush.trigger("ok", new JSONObject());
                }
            });
            return leavePush;
        }

        /**
         * Subscribe to an event on this channel.
         * <p>
         * Thread-safe: may be called from any thread, but note that events fire on
         * the Phoenix HandlerThread so your callback must marshal UI work.
         *
         * @param event    event name, e.g. "new_msg", "presence_diff"
         * @param callback receives (payload, ref, joinRef)
         * @return a binding id — pass to off(event, id) to remove this specific listener
         */
        public int on(String event, MessageCallback callback) {
            // Pre-allocate the id atomically on whatever thread called on().
            // This guarantees three sequential on() calls from the UI thread each
            // get a distinct id (0, 1, 2...) even though the binding is added
            // asynchronously on the Handler thread.
            final int id = nextBindingId.getAndIncrement();
            socket.getHandler().post(() -> bindings.add(new Binding(event, id, callback)));
            return id;
        }

        /**
         * Remove all event listeners for an event.
         * Thread-safe.
         */
        public void off(String event) {
            socket.getHandler().post(() -> removeAllBindings(event));
        }

        /**
         * Remove a specific event listener by the binding id returned from on().
         * Thread-safe.
         */
        public void off(String event, int bindingId) {
            socket.getHandler().post(() -> removeBinding(event, bindingId));
        }

        // State checks — safe to call from any thread (reads a volatile-like field)
        public boolean isClosed() {
            return state == ChannelState.CLOSED;
        }

        public boolean isErrored() {
            return state == ChannelState.ERRORED;
        }

        public boolean isJoined() {
            return state == ChannelState.JOINED;
        }

        public boolean isJoining() {
            return state == ChannelState.JOINING;
        }

        public boolean isLeaving() {
            return state == ChannelState.LEAVING;
        }

        public String getTopic() {
            return topic;
        }

        public ChannelState getState() {
            return state;
        }

        // ═══════════════════════════════════════════
        //  PACKAGE-PRIVATE / INTERNAL
        // ═══════════════════════════════════════════

        /**
         * Add a binding synchronously. Must be called on the Handler thread.
         * <p>
         * This is the only path that mutates bindings. addBinding, removeBinding,
         * and triggerInternal all run on the Handler thread — no extra synchronization needed.
         * <p>
         * Package-private so PhoenixPush can register its refEvent listener directly.
         */
        int addBinding(String event, MessageCallback callback) {
            int id = nextBindingId.getAndIncrement();
            bindings.add(new Binding(event, id, callback));
            return id;
        }

        /**
         * Remove the binding with the given id for the given event.
         * <p>
         * MUST run synchronously on the Handler thread (not posted).
         * Called from PhoenixPush.cancelRefEvent() which is itself on the handler thread.
         * <p>
         * Package-private.
         */
        void removeBinding(String event, int id) {
            Iterator<Binding> it = bindings.iterator();
            while (it.hasNext()) {
                Binding b = it.next();
                if (b.event.equals(event) && b.id == id) {
                    it.remove();
                    return; // ids are unique; stop after first match
                }
            }
        }

        /**
         * Remove ALL bindings for an event (used by the public off(event) API).
         */
        private void removeAllBindings(String event) {
            Iterator<Binding> it = bindings.iterator();
            while (it.hasNext()) {
                if (it.next().event.equals(event)) it.remove();
            }
        }

        /**
         * Route an inbound message to all matching event listeners.
         * <p>
         * Called by PhoenixSocket.onConnMessage() which already runs on the handler thread.
         * Also called internally by phx_reply routing and Push timeout.
         * <p>
         * We snapshot bindings to avoid ConcurrentModificationException if a callback
         * calls on() or off() (which post to the handler and therefore won't execute
         * until after this method returns — but we snapshot for safety in case of
         * direct on-thread calls).
         */
        void triggerInternal(String event, JSONObject payload, String ref, String joinRef) {
            List<Binding> snapshot = new ArrayList<>(bindings);
            for (Binding b : snapshot) {
                if (b.event.equals(event)) {
                    try {
                        b.callback.onMessage(
                                payload,
                                ref,
                                (joinRef != null) ? joinRef : joinRef()
                        );
                    } catch (Exception ex) {
                        socket.log("channel",
                                "Exception in event callback for '" + event +
                                        "' on topic '" + topic + "': " + ex.getMessage());
                        Log.e(TAG, "Event callback threw an exception", ex);
                    }
                }
            }
        }

        /**
         * Returns true if this channel should receive the given inbound message.
         * <p>
         * Topic must match. For lifecycle events, join_ref must also match (or be null),
         * otherwise the message is from a previous channel session and is stale.
         */
        boolean isMember(String msgTopic, String msgEvent,
                         JSONObject payload, String msgJoinRef) {
            // Topic must match
            if (!topic.equals(msgTopic)) return false;

            // join_ref session discrimination:
            // If the message carries a join_ref, and it's a lifecycle event, and it
            // doesn't match our current join session, drop it silently.
            if (msgJoinRef != null
                    && joinRef() != null
                    && !msgJoinRef.equals(joinRef())
                    && ChannelEvent.isLifecycleEvent(msgEvent)) {
                socket.log("channel",
                        "Dropping stale lifecycle message: topic=" + msgTopic +
                                " event=" + msgEvent +
                                " msgJoinRef=" + msgJoinRef +
                                " myJoinRef=" + joinRef());
                return false;
            }
            return true;
        }

        /**
         * The join_ref for the current session.
         * This is the ref assigned to the join push; null before the first send().
         */
        String joinRef() {
            return joinPush.getRef();
        }

        /**
         * Initiate or re-initiate the join sequence.
         * <p>
         * Called by:
         * - join() (first time)
         * - rejoinTimer (after error / disconnect)
         * - socketOnOpen handler (when socket reconnects while channel is ERRORED)
         * <p>
         * Must run on the Handler thread.
         */
        void rejoin() {
            if (isLeaving()) {
                socket.log("channel", "Skipping rejoin — channel is leaving: " + topic);
                return;
            }
            // Prevent duplicate subscriptions: if another Channel instance somehow
            // ended up joined/joining on this topic, leave it first.
            socket.leaveOpenTopic(topic);
            state = ChannelState.JOINING;
            socket.log("channel", "Joining topic: " + topic);
            joinPush.resend(timeout);
        }

        /**
         * Clean up socket-level subscriptions.
         * Called by PhoenixSocket.removeChannel() when the channel moves to CLOSED.
         */
        void destroy() {
            rejoinTimer.reset();
            if (socketOnErrorRef >= 0) {
                socket.removeStateChangeCallback(socketOnErrorRef, "error");
                socketOnErrorRef = -1;
            }
            if (socketOnOpenRef >= 0) {
                socket.removeStateChangeCallback(socketOnOpenRef, "open");
                socketOnOpenRef = -1;
            }
        }

        /**
         * True if the socket is open AND the channel is joined.
         */
        boolean canPush() {
            return socket.isConnected() && isJoined();
        }

        PhoenixSocket getSocket() {
            return socket;
        }

        @Override
        public String toString() {
            return "PhoenixChannel{topic='" + topic + "', state=" + state + "}";
        }
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §6  PHOENIX SOCKET
//
//  Direct Java port of the JS Socket class.
//
//  Responsibilities:
//   - OkHttp WebSocket lifecycle (connect, disconnect, reconnect)
//   - Heartbeat (30s interval, 30s timeout)
//   - Reconnect backoff timer
//   - Message encoding (JSON 5-element array) and routing to channels
//   - Send buffer for pushes queued before the socket is open
//   - connectClock version-stamping to discard stale OkHttp callbacks
//   - Channel registry
//   - State-change subscription management (onOpen, onClose, onError)
//   - Android lifecycle helpers (onActivityPause, onActivityResume)
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Phoenix WebSocket connection manager.
     * <p>
     * Create via the Builder, then call connect().
     * All state mutations happen on the internal HandlerThread.
     */
    public static final class PhoenixSocket {

        private static final String TAG = "PhxSocket";

        // ── Protocol constants ────────────────────────────────────────────────────────
        static final String VSN = "2.0.0";
        static final long DEFAULT_TIMEOUT_MS = 10_000L;
        static final long HEARTBEAT_INTERVAL_MS = 30_000L;
        // How long to wait for a heartbeat reply before declaring the connection dead.
        // Matches JS: same value as heartbeatIntervalMs (i.e., one full interval).
        static final long HEARTBEAT_TIMEOUT_MS = 30_000L;

        // Socket-level reconnect backoff: tries 1-9 use these values (ms), 10+ use 5000 ms.
        // Exact match to JS source: [10, 50, 100, 150, 200, 250, 500, 1000, 2000]
        private static final long[] RECONNECT_DELAYS =
                {10L, 50L, 100L, 150L, 200L, 250L, 500L, 1_000L, 2_000L};

        // ═══════════════════════════════════════════
        //  BUILDER
        // ═══════════════════════════════════════════

        /**
         * Fluent builder for PhoenixSocket.
         * <p>
         * Example:
         * PhoenixSocket socket = new PhoenixSocket.Builder("wss://host/socket")
         * .param("token", authToken)
         * .timeout(10_000)
         * .logger((tag, msg) -> Log.d("PHX", tag + ": " + msg))
         * .build();
         */
        public static final class Builder {
            private final String endpointBase;
            private final Map<String, String> params = new HashMap<>();
            private long timeout = DEFAULT_TIMEOUT_MS;
            private long heartbeatMs = HEARTBEAT_INTERVAL_MS;
            private PhoenixLogger logger = null;

            private MessageDecoder decoder = new MessageDecoder() {
                @Override public String decode(String text) { return text; }
                @Override public String decode(okio.ByteString bytes) { return bytes.utf8(); }
            };

            public Builder decoder(MessageDecoder decoder) {
                this.decoder = decoder;
                return this;
            }

            /**
             * @param endpointBase WebSocket URL base, e.g. "wss://host/socket".
             *                     "/websocket" is appended automatically if absent.
             */
            public Builder(String endpointBase) {
                this.endpointBase = endpointBase;
            }

            /**
             * Add a query parameter sent on every connection (e.g. auth token).
             */
            public Builder param(String key, String value) {
                params.put(key, value);
                return this;
            }

            /**
             * Default push timeout in milliseconds (default 10 000).
             */
            public Builder timeout(long ms) {
                this.timeout = ms;
                return this;
            }

            /**
             * How often to send a heartbeat in milliseconds (default 30 000).
             */
            public Builder heartbeatIntervalMs(long ms) {
                this.heartbeatMs = ms;
                return this;
            }

            /**
             * Provide a custom logger. If not set, android.util.Log.d is used.
             * Set to null to disable logging entirely.
             */
            public Builder logger(PhoenixLogger logger) {
                this.logger = logger;
                return this;
            }

            public PhoenixSocket build() {
                return new PhoenixSocket(endpointBase, params, timeout, heartbeatMs, logger, decoder);
            }
        }

        // ── Configuration ─────────────────────────────────────────────────────────────
        private final String endpointBase;    // normalised, ends with /websocket
        private final Map<String, String> params;
        private final long timeout;         // default push timeout
        private final long heartbeatIntervalMs;
        private final PhoenixLogger logger;
        private final MessageDecoder decoder;

        // ── Runtime state ──────────────────────────────────────────────────────────────
        //
        // EVERY field below is accessed ONLY from the HandlerThread.
        // We do not use volatile or synchronized because the HandlerThread is the
        // sole owner. If you add new fields, obey this rule.

        private WebSocket conn = null;  // null = not connected
        private final List<PhoenixChannel> channels = new ArrayList<>();
        private final Queue<Runnable> sendBuffer = new LinkedList<>();
        private final AtomicInteger refCounter = new AtomicInteger(0);

        // Heartbeat state
        private String pendingHeartbeatRef = null;
        private Runnable heartbeatRunnable = null;
        private Runnable heartbeatTimeoutRun = null;

        // Connection lifecycle flags
        private boolean closeWasClean = true;  // true = we initiated disconnect; suppresses auto-reconnect
        private boolean disconnecting = false; // true = disconnect() is in progress
        private int connectClock = 0;     // incremented on every new connection attempt
        private int establishedConns = 0;     // how many successful connections we've had total

        // Android lifecycle
        private boolean pageHidden = false; // true when app is in background
        private Runnable backgroundDisconnectRunnable = null;  // fires teardown if user stays in background too long

        // Reconnect timer
        private final RetryTimer reconnectTimer;

        // ── Handler thread ─────────────────────────────────────────────────────────────
        private final HandlerThread handlerThread;
        private final Handler handler;

        // ── OkHttp ─────────────────────────────────────────────────────────────────────
        private final OkHttpClient httpClient;

        // ── State-change subscriptions ─────────────────────────────────────────────────
        // Each subscription has an integer id. We keep parallel id/callback lists so we
        // can remove by id. Access only from the Handler thread.
        private final List<Integer> openIds = new ArrayList<>();
        private final List<OpenCallback> openCbs = new ArrayList<>();
        private final List<Integer> closeIds = new ArrayList<>();
        private final List<CloseCallback> closeCbs = new ArrayList<>();
        private final List<Integer> errorIds = new ArrayList<>();
        private final List<ErrorCallback> errorCbs = new ArrayList<>();
        private int nextStateChangeRef = 0;

        // ── Constructor ─────────────────────────────────────────────────────────────────

        /**
         * Internal constructor — use the Builder.
         */
        PhoenixSocket(String endpointBase,
                      Map<String, String> params,
                      long timeout,
                      long heartbeatIntervalMs,
                      PhoenixLogger logger,
                      MessageDecoder decoder) {
            // Normalise: strip trailing slash, ensure /websocket suffix
            String url = endpointBase.replaceAll("/+$", "");
            if (!url.endsWith("/websocket")) url = url + "/websocket";
            this.endpointBase = url;
            this.params = new HashMap<>(params);
            this.timeout = timeout;
            this.heartbeatIntervalMs = heartbeatIntervalMs;

            // Default to Android Log if no logger provided
            this.logger = (logger != null) ? logger
                    : (tag, msg) -> Log.d(TAG, "[" + tag + "] " + msg);
            this.decoder = decoder;

            // ── Handler thread ──
            handlerThread = new HandlerThread("PhxSocket");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());

            // ── OkHttp ──
            // readTimeout(0) = never timeout reads (WebSocket frames arrive asynchronously)
            // We manage application-level keepalive via Phoenix heartbeat
            httpClient = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(0, TimeUnit.MILLISECONDS) // disable OkHttp pings; Phoenix heartbeat handles this
                    .build();

            // ── Reconnect timer ──
            // Delays: [10, 50, 100, 150, 200, 250, 500, 1000, 2000] ms, then 5000 ms
            reconnectTimer = new RetryTimer(handler, () -> {
                if (pageHidden) {
                    // Don't reconnect while the app is in the background.
                    // onActivityResume() will trigger reconnect when user comes back.
                    log("transport",
                            "Skipping reconnect attempt — app is in background");
                    return;
                }
                log("transport", "Reconnect timer fired; tearing down and reconnecting");
                teardown(() -> transportConnect(), 5);
            }, tries -> {
                if (tries <= RECONNECT_DELAYS.length) {
                    return RECONNECT_DELAYS[tries - 1];
                }
                return 5_000L;
            });
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API — CONNECTION
        // ═══════════════════════════════════════════

        /**
         * Connect to the Phoenix server.
         * <p>
         * Safe to call multiple times — no-op if already connected or connecting.
         * Also safe to call from any thread.
         */
        public void connect() {
            handler.post(() -> {
                if (conn != null && !disconnecting) {
                    log("transport", "connect() called but already connected — ignoring");
                    return;
                }
                transportConnect();
            });
        }

        /**
         * Disconnect intentionally.
         * <p>
         * Sets closeWasClean = true so the reconnect timer does NOT fire.
         * Thread-safe.
         *
         * @param callback optional; called on the HandlerThread after disconnect completes
         */
        public void disconnect(Runnable callback) {
            handler.post(() -> {
                log("transport", "Disconnecting intentionally");
                connectClock++;          // invalidate any pending connection attempt
                disconnecting = true;
                closeWasClean = true;   // suppress auto-reconnect
                reconnectTimer.reset();
                clearHeartbeats();
                teardown(() -> {
                    disconnecting = false;
                    if (callback != null) callback.run();
                }, 5);
            });
        }

        /**
         * Disconnect without a callback.
         */
        public void disconnect() {
            disconnect(null);
        }

        /**
         * Shut down the socket completely — releases the HandlerThread and OkHttp
         * thread pool. Call in Activity.onDestroy() or ViewModel.onCleared().
         * <p>
         * After calling shutdown(), this object should not be reused.
         */
        public void shutdown() {
            disconnect(() -> {
                log("transport", "Shutting down HandlerThread and OkHttp");
                handlerThread.quitSafely();
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
            });
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API — ANDROID LIFECYCLE HELPERS
        // ═══════════════════════════════════════════

        /**
         * Call this from Activity.onStop() or Fragment.onStop().
         * <p>
         * Disconnects the socket so it doesn't drain battery while the app is hidden.
         * Sets pageHidden = true so the reconnect timer won't fire unnecessarily.
         * The close is NOT clean (closeWasClean = false) so reconnect happens on resume.
         */
//        public void onActivityPause() {
//            handler.post(() -> {
//                log("transport", "App paused — disconnecting to save battery");
//                pageHidden = true;
//                closeWasClean = false; // allow reconnect on resume
//                reconnectTimer.reset();
//                clearHeartbeats();
//                if (conn != null) {
//                    teardown(null, 5);
//                }
//            });
//        }

        public void onActivityPause() {
            handler.post(() -> {
                log("transport", "onActivityPause() called — app going to background");
                pageHidden    = true;
                closeWasClean = false;
                reconnectTimer.reset();
                clearHeartbeats();
                log("transport", "Heartbeats cleared, reconnect timer reset");

                // Cancel any previously scheduled background disconnect
                // (safety: in case onActivityPause is called twice)
                if (backgroundDisconnectRunnable != null) {
                    handler.removeCallbacks(backgroundDisconnectRunnable);
                    backgroundDisconnectRunnable = null;
                    log("transport", "Cancelled previous background disconnect timer");
                }

                if (conn == null) {
                    log("transport", "Socket already disconnected — skipping background timer");
                    return;
                }

                // Schedule disconnect after 30s if user stays in background
                backgroundDisconnectRunnable = () -> {
                    if (pageHidden && conn != null) {
                        log("transport", "Background timeout reached (30s) — disconnecting to save battery");
                        teardown(null, 5);
                    } else if (!pageHidden) {
                        log("transport", "Background timer fired but app is foreground — skipping teardown");
                    } else {
                        log("transport", "Background timer fired but socket already gone — nothing to do");
                    }
                    backgroundDisconnectRunnable = null;
                };
                handler.postDelayed(backgroundDisconnectRunnable, 30_000L);
                log("transport", "Background disconnect scheduled in 30 seconds");
            });
        }

        /**
         * Call this from Activity.onStart() or Fragment.onStart().
         * <p>
         * If the last disconnect was not clean (i.e. the OS killed it or the user
         * just came back from background), reconnects the socket.
         * Channels will rejoin automatically after reconnect.
         */
        public void onActivityResume() {
            handler.post(() -> {
                log("transport", "onActivityResume() called — app coming to foreground");
                pageHidden = false;

                // Cancel the pending background disconnect if it hasn't fired yet
                if (backgroundDisconnectRunnable != null) {
                    handler.removeCallbacks(backgroundDisconnectRunnable);
                    backgroundDisconnectRunnable = null;
                    log("transport", "Background disconnect timer cancelled — user returned in time");
                }

                if (isConnected()) {
                    log("transport", "Socket still alive — no reconnect needed");
                } else if (closeWasClean) {
                    log("transport", "Socket was cleanly disconnected — not reconnecting automatically");
                } else {
                    log("transport", "Socket lost while in background — reconnecting now");
                    teardown(() -> transportConnect(), 5);
                }
            });
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API — CHANNELS
        // ═══════════════════════════════════════════

        /**
         * Create (but do NOT join) a channel for the given topic.
         * <p>
         * Thread-safe. The channel is added to the socket's internal registry
         * asynchronously via the handler.
         *
         * @param topic      e.g. "room:lobby" or "user:123"
         * @param chanParams params sent up with the join message (auth info, etc.)
         */
        public PhoenixChannel channel(String topic, JSONObject chanParams) {
            PhoenixChannel chan = new PhoenixChannel(topic, chanParams, this);
            // Add to registry on the handler thread for ordering correctness
            handler.post(() -> {
                log("socket", "Registering channel: " + topic);
                channels.add(chan);
            });
            return chan;
        }

        /**
         * Convenience overload — no params.
         */
        public PhoenixChannel channel(String topic) {
            return channel(topic, null);
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API — STATE CHANGE SUBSCRIPTIONS
        // ═══════════════════════════════════════════

        /**
         * Register a callback to be fired each time the socket successfully opens
         * (including reconnects).
         *
         * @return subscription id — pass to removeStateChangeCallback() to unsubscribe
         */
        public int onOpen(OpenCallback callback) {
            int id = nextStateChangeRef++;
            handler.post(() -> {
                openIds.add(id);
                openCbs.add(callback);
            });
            return id;
        }

        /**
         * Register a callback for socket close events.
         *
         * @return subscription id
         */
        public int onClose(CloseCallback callback) {
            int id = nextStateChangeRef++;
            handler.post(() -> {
                closeIds.add(id);
                closeCbs.add(callback);
            });
            return id;
        }

        /**
         * Register a callback for socket error events.
         *
         * @return subscription id
         */
        public int onError(ErrorCallback callback) {
            int id = nextStateChangeRef++;
            handler.post(() -> {
                errorIds.add(id);
                errorCbs.add(callback);
            });
            return id;
        }

        // ═══════════════════════════════════════════
        //  PUBLIC API — STATUS
        // ═══════════════════════════════════════════

        /**
         * Returns true if the WebSocket is currently open.
         * <p>
         * NOTE: This reads the conn field which is only mutated on the handler thread.
         * Calling this from a non-handler thread gives you the "last known" state which
         * is fine for UI display but should not be used for push logic (the push() method
         * handles that correctly via its own handler-posted logic).
         */
        public boolean isConnected() {
            return conn != null;
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — TRANSPORT CONNECT
        // ═══════════════════════════════════════════

        /**
         * Create a new OkHttp WebSocket connection.
         * <p>
         * Must run on the Handler thread.
         * Increments connectClock so stale OkHttp callbacks from previous attempts
         * can be identified and discarded.
         */
        private void transportConnect() {
            connectClock++;
            closeWasClean = false;
            final int clock = connectClock; // captured into OkHttp listener lambdas

            String url = buildUrl();
            log("transport", "Connecting to: " + url + " (clock=" + clock + ")");

            Request request = new Request.Builder().url(url).build();
            // Temporarily assign conn so isConnected() returns true while connecting.
            // The real WebSocket object replaces it in onOpen.
            conn = httpClient.newWebSocket(request, new WebSocketListener() {

                @Override
                public void onOpen(WebSocket ws, Response response) {
                    // Marshal to handler thread IMMEDIATELY
                    handler.post(() -> {
                        if (clock != connectClock) {
                            log("transport", "Discarding stale onOpen (clock=" + clock + ")");
                            ws.close(1000, "stale");
                            return;
                        }
                        // Replace the stub conn with the real, confirmed-open WebSocket
                        conn = ws;
                        onConnOpen();
                    });
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    try {
                        final String decoded = decoder.decode(text);
                        if (decoded != null && !decoded.isEmpty()) {
                            handler.post(() -> {
                                if (clock != connectClock) return;
                                onConnMessage(decoded);
                            });
                        }
                    } catch (Exception e) {
                        log("transport", "Decoder failed on text message: " + e.getMessage());
                    }
                }

                @Override
                public void onMessage(WebSocket ws, okio.ByteString bytes) {
                    try {
                        final String decoded = decoder.decode(bytes);
                        if (decoded != null && !decoded.isEmpty()) {
                            handler.post(() -> {
                                if (clock != connectClock) return;
                                onConnMessage(decoded);
                            });
                        }
                    } catch (Exception e) {
                        log("transport", "Decoder failed on binary message: " + e.getMessage());
                    }
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    // Server initiated close — acknowledge it
                    ws.close(code, reason);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    handler.post(() -> {
                        if (clock != connectClock) {
                            log("transport", "Discarding stale onClosed (clock=" + clock + ")");
                            return;
                        }
                        conn = null;
                        onConnClose(code, reason);
                    });
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    handler.post(() -> {
                        if (clock != connectClock) {
                            log("transport", "Discarding stale onFailure (clock=" + clock + ")");
                            return;
                        }
                        conn = null;
                        String reason = (t.getMessage() != null) ? t.getMessage() : t.toString();
                        log("transport", "Connection failure: " + reason);
                        onConnError(reason);
                        // Schedule reconnect unless we intentionally disconnected
                        if (!closeWasClean) {
                            reconnectTimer.scheduleTimeout();
                        }
                    });
                }
            });
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — TEARDOWN
        // ═══════════════════════════════════════════

        /**
         * Gracefully close the WebSocket, waiting for the send buffer to drain.
         * <p>
         * Polls conn.queueSize() up to maxTries × 150ms intervals (mirrors JS
         * "waitForBufferDone" pattern). Then calls ws.close(1000).
         * Invokes callback when done.
         * <p>
         * Must run on the Handler thread.
         */
        private void teardown(Runnable onDone, int triesLeft) {
            clearHeartbeats();

            if (conn == null) {
                // Nothing to close
                if (onDone != null) handler.post(onDone);
                return;
            }

            WebSocket toClose = conn;
            conn = null; // Immediately null so isConnected() returns false

            if (triesLeft <= 0 || toClose.queueSize() == 0) {
                // Buffer is empty (or we've run out of patience) — close now
                toClose.close(1000, "ok");
                if (onDone != null) handler.post(onDone);
            } else {
                // Give the send buffer a bit more time to drain before closing
                handler.postDelayed(() -> teardown(onDone, triesLeft - 1), 150L);
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — CONNECTION EVENT HANDLERS
        // ═══════════════════════════════════════════

        /**
         * Called when the WebSocket successfully opens.
         * Must run on the Handler thread.
         */
        private void onConnOpen() {
            log("transport", "Socket opened. Established connections: " + (establishedConns + 1));
            closeWasClean = false;
            disconnecting = false;
            establishedConns++;
            flushSendBuffer();
            reconnectTimer.reset();
            resetHeartbeat();
            // Notify all onOpen subscribers
            for (OpenCallback cb : new ArrayList<>(openCbs)) {
                try {
                    cb.onOpen();
                } catch (Exception ex) {
                    Log.e(TAG, "onOpen callback threw", ex);
                }
            }
        }

        /**
         * Called when the WebSocket closes (normal or abnormal).
         * Must run on the Handler thread.
         */
        private void onConnClose(int code, String reason) {
            log("transport", "Socket closed. code=" + code + " reason='" + reason + "'");
            // Trigger error on all live channels so they schedule rejoin
            triggerChanError();
            clearHeartbeats();
            // Schedule reconnect unless we closed cleanly (i.e. disconnect() was called)
            if (!closeWasClean && code != 1000) {
                log("transport", "Abnormal close — scheduling reconnect");
                reconnectTimer.scheduleTimeout();
            }
            for (CloseCallback cb : new ArrayList<>(closeCbs)) {
                try {
                    cb.onClose(code, (reason != null) ? reason : "");
                } catch (Exception ex) {
                    Log.e(TAG, "onClose callback threw", ex);
                }
            }
        }

        /**
         * Called when the WebSocket encounters an error.
         * Must run on the Handler thread.
         */
        private void onConnError(String error) {
            log("transport", "Socket error: " + error);
            for (ErrorCallback cb : new ArrayList<>(errorCbs)) {
                try {
                    cb.onError(error);
                } catch (Exception ex) {
                    Log.e(TAG, "onError callback threw", ex);
                }
            }
            // Also trigger channel errors (channels should start rejoin timers)
            triggerChanError();
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — MESSAGE ROUTING
        // ═══════════════════════════════════════════

        /**
         * Parse an inbound message and route it to the correct channel(s).
         * <p>
         * Wire format: [join_ref, ref, topic, event, payload]
         * Must run on the Handler thread.
         */
        private void onConnMessage(String text) {
            try {
                JSONArray arr = new JSONArray(text);

                // Deserialise the 5-element array
                String joinRef = arr.isNull(0) ? null : arr.getString(0);
                String ref = arr.isNull(1) ? null : arr.getString(1);
                String topic = arr.getString(2);
                String event = arr.getString(3);
                JSONObject payload;
                if (arr.isNull(4)) {
                    payload = new JSONObject();
                } else {
                    payload = arr.optJSONObject(4);
                    if (payload == null) payload = new JSONObject();
                }

                log("receive",
                        topic + " " + event +
                                (ref != null ? " ref=" + ref : "") +
                                (joinRef != null ? " join_ref=" + joinRef : ""));

                // ── Heartbeat reply ──────────────────────────────────────────────
                // The server replies to our heartbeat with event="phx_reply" on
                // topic="phoenix". We detect this by ref match and reset the heartbeat timer.
                if (ref != null && ref.equals(pendingHeartbeatRef)) {
                    log("heartbeat", "Heartbeat reply received (ref=" + ref + ")");
                    clearHeartbeats();
                    pendingHeartbeatRef = null;
                    // Schedule the next heartbeat after the interval
                    scheduleNextHeartbeat();
                    return; // Don't route heartbeat replies to channels
                }

                // ── Route to channels ────────────────────────────────────────────
                // Snapshot channels list in case a callback modifies it
                List<PhoenixChannel> snapshot = new ArrayList<>(channels);
                for (PhoenixChannel ch : snapshot) {
                    if (ch.isMember(topic, event, payload, joinRef)) {
                        ch.triggerInternal(event, payload, ref, joinRef);
                    }
                }

            } catch (Exception e) {
                log("transport", "Failed to parse inbound message: " + text + " error=" + e.getMessage());
                Log.e(TAG, "JSON parse error on inbound message", e);
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — HEARTBEAT
        // ═══════════════════════════════════════════

        /**
         * Reset the heartbeat state and schedule the first heartbeat send.
         * Called after socket opens.
         */
        private void resetHeartbeat() {
            pendingHeartbeatRef = null;
            clearHeartbeats();
            scheduleNextHeartbeat();
        }

        /**
         * Schedule the heartbeat runnable to fire after heartbeatIntervalMs.
         */
        private void scheduleNextHeartbeat() {
            heartbeatRunnable = this::sendHeartbeat;
            handler.postDelayed(heartbeatRunnable, heartbeatIntervalMs);
        }

        /**
         * Send a heartbeat to the server and start the death-timer.
         * <p>
         * If pendingHeartbeatRef is still set from a previous heartbeat, it means
         * the server never replied — the connection is silently dead. Tear it down.
         */
        private void sendHeartbeat() {
            if (!isConnected()) return;

            if (pendingHeartbeatRef != null) {
                // Previous heartbeat went unanswered — connection is dead
                log("heartbeat",
                        "No reply to heartbeat ref=" + pendingHeartbeatRef +
                                " — connection appears dead; reconnecting");
                pendingHeartbeatRef = null;
                triggerChanError();
                closeWasClean = false;
                teardown(() -> reconnectTimer.scheduleTimeout(), 5);
                return;
            }

            // Send heartbeat
            pendingHeartbeatRef = makeRef();
            log("heartbeat", "Sending heartbeat (ref=" + pendingHeartbeatRef + ")");
            pushRaw("phoenix", "heartbeat", new JSONObject(), pendingHeartbeatRef, null);

            // Arm the timeout bomb: if no reply within HEARTBEAT_TIMEOUT_MS, reconnect
            heartbeatTimeoutRun = () -> {
                if (pendingHeartbeatRef == null) return; // already replied
                log("heartbeat", "Heartbeat timeout — no reply within " + HEARTBEAT_TIMEOUT_MS + "ms");
                pendingHeartbeatRef = null;
                triggerChanError();
                closeWasClean = false;
                teardown(() -> reconnectTimer.scheduleTimeout(), 5);
            };
            handler.postDelayed(heartbeatTimeoutRun, HEARTBEAT_TIMEOUT_MS);
        }

        /**
         * Cancel all pending heartbeat runnables.
         */
        private void clearHeartbeats() {
            if (heartbeatRunnable != null) {
                handler.removeCallbacks(heartbeatRunnable);
                heartbeatRunnable = null;
            }
            if (heartbeatTimeoutRun != null) {
                handler.removeCallbacks(heartbeatTimeoutRun);
                heartbeatTimeoutRun = null;
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — SEND
        // ═══════════════════════════════════════════

        /**
         * Push a message from a channel to the server.
         * <p>
         * If not connected, queues the message in sendBuffer.
         * sendBuffer is drained in onConnOpen() via flushSendBuffer().
         * <p>
         * Must run on the Handler thread (called from PhoenixPush.send()).
         */
        void pushMessage(String topic, String event, JSONObject payload,
                         String ref, String joinRef) {
            String encoded = encode(topic, event, payload, ref, joinRef);
            if (isConnected()) {
                conn.send(encoded);
            } else {
                log("socket", "Queuing message (socket offline): " + topic + " " + event);
                // Capture encoded string, not the JSONObject, because JSONObject is mutable
                final String captured = encoded;
                sendBuffer.add(() -> {
                    if (conn != null) conn.send(captured);
                });
            }
        }

        /**
         * Push a raw message directly (used for heartbeat).
         * Must run on the Handler thread.
         */
        private void pushRaw(String topic, String event, JSONObject payload,
                             String ref, String joinRef) {
            if (isConnected()) {
                conn.send(encode(topic, event, payload, ref, joinRef));
            }
        }

        /**
         * Drain and execute all queued send runnables.
         */
        private void flushSendBuffer() {
            if (!isConnected()) return;
            log("socket", "Flushing send buffer (" + sendBuffer.size() + " items)");
            Runnable r;
            while ((r = sendBuffer.poll()) != null) {
                try {
                    r.run();
                } catch (Exception ex) {
                    Log.e(TAG, "Exception flushing send buffer", ex);
                }
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — SERIALISER
        // ═══════════════════════════════════════════

        /**
         * Encode a message as the Phoenix 5-element JSON array.
         * Format: [join_ref, ref, topic, event, payload]
         * <p>
         * null values for join_ref and ref are encoded as JSON null (JSONObject.NULL),
         * which the server expects.
         */
        private String encode(String topic, String event, JSONObject payload,
                              String ref, String joinRef) {
            JSONArray arr = new JSONArray();
            try {
                arr.put(joinRef != null ? joinRef : JSONObject.NULL);
                arr.put(ref != null ? ref : JSONObject.NULL);
                arr.put(topic);
                arr.put(event);
                arr.put(payload != null ? payload : new JSONObject());
            } catch (Exception e) {
                Log.e(TAG, "Serialisation error", e);
            }
            return arr.toString();
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — CHANNEL MANAGEMENT
        // ═══════════════════════════════════════════

        /**
         * If there is already a JOINED or JOINING channel for this topic, leave it.
         * <p>
         * Called by PhoenixChannel.rejoin() before sending phx_join.
         * Prevents duplicate server-side channel processes for the same topic.
         */
        void leaveOpenTopic(String topic) {
            List<PhoenixChannel> snapshot = new ArrayList<>(channels);
            for (PhoenixChannel ch : snapshot) {
                if (ch.getTopic().equals(topic) && (ch.isJoined() || ch.isJoining())) {
                    log("transport", "Leaving duplicate topic before rejoin: " + topic);
                    ch.leave();
                    break; // There should only be one, but break defensively
                }
            }
        }

        /**
         * Remove a channel from the registry.
         * Called by PhoenixChannel's phx_close handler when state transitions to CLOSED.
         */
        void removeChannel(PhoenixChannel channel) {
            log("socket", "Removing channel: " + channel.getTopic());
            channel.destroy();
            channels.remove(channel);
        }

        /**
         * Trigger an error on all channels that are still active.
         * <p>
         * Skips channels in ERRORED, LEAVING, or CLOSED state — they are already
         * handling their own error or shutdown sequence.
         */
        private void triggerChanError() {
            List<PhoenixChannel> snapshot = new ArrayList<>(channels);
            for (PhoenixChannel ch : snapshot) {
                if (!ch.isErrored() && !ch.isLeaving() && !ch.isClosed()) {
                    ch.triggerInternal(ChannelEvent.ERROR, new JSONObject(), null, null);
                }
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — STATE CHANGE MANAGEMENT
        // ═══════════════════════════════════════════

        /**
         * Remove a state-change subscription by id.
         * <p>
         * Called by PhoenixChannel.destroy() to clean up its socket subscriptions.
         * Must run on the Handler thread (channel.destroy() is called from the handler).
         */
        void removeStateChangeCallback(int id, String type) {
            switch (type) {
                case "open":
                    removeById(id, openIds, openCbs);
                    break;
                case "close":
                    removeById(id, closeIds, closeCbs);
                    break;
                case "error":
                    removeById(id, errorIds, errorCbs);
                    break;
                default:
                    log("socket", "Unknown state change type: " + type);
            }
        }

        private <T> void removeById(int id, List<Integer> ids, List<T> cbs) {
            int idx = ids.indexOf(id);
            if (idx >= 0) {
                ids.remove(idx);
                cbs.remove(idx);
            }
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — REFS
        // ═══════════════════════════════════════════

        /**
         * Generate the next unique message ref string.
         * <p>
         * Uses AtomicInteger.updateAndGet for a correct compare-and-update.
         * Wraps back to 1 at Integer.MAX_VALUE (never returns 0 so null checks
         * on ref remain meaningful).
         * <p>
         * Thread-safe; may be called from any thread (PhoenixPush.startTimeout()
         * always runs on the handler thread, but the atomic op is safe regardless).
         */
        String makeRef() {
            int next = refCounter.updateAndGet(v -> (v >= Integer.MAX_VALUE) ? 1 : v + 1);
            return String.valueOf(next);
        }

        /**
         * Compute the synthetic event name for reply routing.
         * e.g. ref="42" → "chan_reply_42"
         */
        static String replyEventName(String ref) {
            return "chan_reply_" + ref;
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — URL
        // ═══════════════════════════════════════════

        private String buildUrl() {
            StringBuilder sb = new StringBuilder(endpointBase);
            sb.append("?vsn=").append(VSN);
            for (Map.Entry<String, String> e : params.entrySet()) {
                try {
                    sb.append('&')
                            .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8.name()))
                            .append('=')
                            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8.name()));
                } catch (Exception ignored) {
                    // URLEncoder.encode() with a hardcoded "UTF-8" never throws in practice
                    sb.append('&').append(e.getKey()).append('=').append(e.getValue());
                }
            }
            return sb.toString();
        }

        // ═══════════════════════════════════════════
        //  INTERNAL — LOGGING
        // ═══════════════════════════════════════════

        /**
         * Log via the configured logger. Null-safe.
         */
        void log(String tag, String msg) {
            if (logger != null) {
                try {
                    logger.log(tag, msg);
                } catch (Exception ignored) {
                }
            }
        }

        // ═══════════════════════════════════════════
        //  ACCESSORS (package-private)
        // ═══════════════════════════════════════════

        Handler getHandler() {
            return handler;
        }

        long getTimeout() {
            return timeout;
        }

        @Override
        public String toString() {
            return "PhoenixSocket{url='" + endpointBase +
                    "', connected=" + isConnected() +
                    ", channels=" + channels.size() +
                    ", clock=" + connectClock + "}";
        }
    }


// ═══════════════════════════════════════════════════════════════════════════════
//  §7  PHOENIX PRESENCE
//
//  Syncs presence state from the server. This is a direct Java port of the JS
//  Presence class from presence.js.
//
//  Usage:
//    PhoenixChannel channel = socket.channel("room:lobby", null);
//    PhoenixPresence presence = new PhoenixPresence(channel);
//
//    presence.onJoin((id, currentPresence, newPresence) -> {
//        Log.d(TAG, "User joined: " + id);
//    });
//    presence.onLeave((id, currentPresence, leftPresence) -> {
//        Log.d(TAG, "User left: " + id);
//    });
//    presence.onSync(() -> {
//        // State has been updated — re-render your user list
//        Map<String, JSONObject> users = presence.getState();
//        runOnUiThread(() -> updateUserList(users));
//    });
//
//    channel.join();
// ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Maintains a synced view of connected users/presences for a channel.
     * <p>
     * Handles the "presence_state" (full state sync on join) and
     * "presence_diff" (incremental join/leave events) messages from the server.
     * <p>
     * Correctly handles the "pending sync" window where presence_diff events
     * arrive before the initial presence_state has been processed.
     * <p>
     * THREADING: all callbacks fire on the Phoenix HandlerThread.
     */
    public static final class PhoenixPresence {

        private static final String TAG = "PhxPresence";

        /**
         * Callback for individual presence join/leave events.
         */
        public interface PresenceChangeCallback {
            /**
             * @param id              the presence key (usually user id)
             * @param currentPresence the existing presence data before the change
             *                        (null if this is a brand-new join)
             * @param newPresence     the incoming presence data
             */
            void onChange(String id, JSONObject currentPresence, JSONObject newPresence);
        }

        private final PhoenixChannel channel;

        // Local presence state map: key → presence JSON object containing "metas" array
        private final Map<String, JSONObject> state = new HashMap<>();

        // Diffs that arrived while we were waiting for the initial state sync
        private final List<JSONObject> pendingDiffs = new ArrayList<>();

        // The join_ref at the time of the last presence_state message.
        // If this differs from channel.joinRef(), we are in a "pending sync" state.
        private String joinRef = null;

        // User-supplied callbacks
        private PresenceChangeCallback onJoin = null;
        private PresenceChangeCallback onLeave = null;
        private Runnable onSync = null;

        public PhoenixPresence(PhoenixChannel channel) {
            this.channel = channel;

            // ── presence_state: full snapshot from the server ─────────────────────
            // Fires when we first join the channel, or when we rejoin after a reconnect.
            channel.on("presence_state", (newState, ref, newJoinRef) -> {
                joinRef = channel.joinRef();
                // Merge the server's full snapshot with our local state
                syncState(state, newState, onJoin, onLeave);
                // Apply any diffs that arrived while we were waiting for this snapshot
                for (JSONObject diff : pendingDiffs) {
                    syncDiff(state, diff, onJoin, onLeave);
                }
                pendingDiffs.clear();
                if (onSync != null) onSync.run();
            });

            // ── presence_diff: incremental join/leave events ──────────────────────
            channel.on("presence_diff", (diff, ref, diffJoinRef) -> {
                if (inPendingSyncState()) {
                    // We haven't received the initial state yet — buffer this diff
                    pendingDiffs.add(diff);
                } else {
                    syncDiff(state, diff, onJoin, onLeave);
                    if (onSync != null) onSync.run();
                }
            });
        }

        // ── Public API ─────────────────────────────────────────────────────────────

        /**
         * Register callback for a new presence joining.
         */
        public void onJoin(PresenceChangeCallback callback) {
            this.onJoin = callback;
        }

        /**
         * Register callback for a presence leaving.
         */
        public void onLeave(PresenceChangeCallback callback) {
            this.onLeave = callback;
        }

        /**
         * Register callback fired every time the state is updated.
         */
        public void onSync(Runnable callback) {
            this.onSync = callback;
        }

        /**
         * Returns the current presence state map. Snapshot the result if you need stability.
         */
        public Map<String, JSONObject> getState() {
            return state;
        }

        // ── Internal ───────────────────────────────────────────────────────────────

        /**
         * True if we haven't received the initial presence_state for this session yet.
         */
        private boolean inPendingSyncState() {
            return joinRef == null || !joinRef.equals(channel.joinRef());
        }

        /**
         * Reconcile a full server state snapshot with the local state.
         * <p>
         * Computes which presences joined (in newState but not in current, or with
         * new phx_ref values) and which left (in current but not in newState), then
         * calls syncDiff() with the computed diff.
         * <p>
         * This is a direct port of JS Presence.syncState().
         */
        private static void syncState(Map<String, JSONObject> state,
                                      JSONObject newState,
                                      PresenceChangeCallback onJoin,
                                      PresenceChangeCallback onLeave) {
            try {
                JSONObject joins = new JSONObject();
                JSONObject leaves = new JSONObject();

                // Find presences that have left entirely
                for (String key : state.keySet()) {
                    if (!newState.has(key)) {
                        leaves.put(key, state.get(key));
                    }
                }

                // Find presences that have joined or have new meta entries
                Iterator<String> newKeys = newState.keys();
                while (newKeys.hasNext()) {
                    String key = newKeys.next();
                    JSONObject newPresence = newState.getJSONObject(key);
                    JSONObject curPresence = state.get(key);

                    if (curPresence != null) {
                        // Presence exists — check for individual meta changes
                        JSONArray newMetas = newPresence.getJSONArray("metas");
                        JSONArray curMetas = curPresence.getJSONArray("metas");

                        // Metas in newState not in current → joined
                        JSONArray joinedMetas = new JSONArray();
                        for (int i = 0; i < newMetas.length(); i++) {
                            String phxRef = newMetas.getJSONObject(i).optString("phx_ref");
                            if (!containsRef(curMetas, phxRef)) {
                                joinedMetas.put(newMetas.getJSONObject(i));
                            }
                        }
                        if (joinedMetas.length() > 0) {
                            JSONObject joinEntry = new JSONObject(newPresence.toString());
                            joinEntry.put("metas", joinedMetas);
                            joins.put(key, joinEntry);
                        }

                        // Metas in current not in newState → left
                        JSONArray leftMetas = new JSONArray();
                        for (int i = 0; i < curMetas.length(); i++) {
                            String phxRef = curMetas.getJSONObject(i).optString("phx_ref");
                            if (!containsRef(newMetas, phxRef)) {
                                leftMetas.put(curMetas.getJSONObject(i));
                            }
                        }
                        if (leftMetas.length() > 0) {
                            JSONObject leaveEntry = new JSONObject(curPresence.toString());
                            leaveEntry.put("metas", leftMetas);
                            leaves.put(key, leaveEntry);
                        }
                    } else {
                        // Brand new presence
                        joins.put(key, newPresence);
                    }
                }

                // Apply the computed diff
                JSONObject diff = new JSONObject();
                diff.put("joins", joins);
                diff.put("leaves", leaves);
                syncDiff(state, diff, onJoin, onLeave);

            } catch (Exception e) {
                Log.e(TAG, "Error in syncState", e);
            }
        }

        /**
         * Apply a presence diff (joins and leaves) to the local state.
         * <p>
         * This is a direct port of JS Presence.syncDiff().
         */
        private static void syncDiff(Map<String, JSONObject> state,
                                     JSONObject diff,
                                     PresenceChangeCallback onJoin,
                                     PresenceChangeCallback onLeave) {
            try {
                // ── Process joins ────────────────────────────────────────────────
                JSONObject joins = diff.optJSONObject("joins");
                if (joins != null) {
                    Iterator<String> keys = joins.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject newPresence = joins.getJSONObject(key);
                        JSONObject curPresence = state.get(key);

                        // Merge: prepend existing metas not already in the new entry
                        JSONObject updated = new JSONObject(newPresence.toString());
                        if (curPresence != null) {
                            JSONArray joinedRefs = updated.getJSONArray("metas");
                            JSONArray curMetas = curPresence.getJSONArray("metas");
                            // Prepend existing metas that aren't in the new entry
                            JSONArray merged = new JSONArray();
                            for (int i = 0; i < curMetas.length(); i++) {
                                String phxRef = curMetas.getJSONObject(i).optString("phx_ref");
                                if (!containsRef(joinedRefs, phxRef)) {
                                    merged.put(curMetas.getJSONObject(i));
                                }
                            }
                            // Add new metas after existing ones
                            for (int i = 0; i < joinedRefs.length(); i++) {
                                merged.put(joinedRefs.getJSONObject(i));
                            }
                            updated.put("metas", merged);
                        }
                        state.put(key, updated);

                        if (onJoin != null) {
                            onJoin.onChange(key, curPresence, newPresence);
                        }
                    }
                }

                // ── Process leaves ───────────────────────────────────────────────
                JSONObject leaves = diff.optJSONObject("leaves");
                if (leaves != null) {
                    Iterator<String> keys = leaves.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject leftPresence = leaves.getJSONObject(key);
                        JSONObject curPresence = state.get(key);
                        if (curPresence == null) continue; // already gone

                        // Remove the metas that left
                        JSONArray refsToRemove = leftPresence.getJSONArray("metas");
                        JSONArray curMetas = curPresence.getJSONArray("metas");
                        JSONArray keptMetas = new JSONArray();
                        for (int i = 0; i < curMetas.length(); i++) {
                            String phxRef = curMetas.getJSONObject(i).optString("phx_ref");
                            if (!containsRef(refsToRemove, phxRef)) {
                                keptMetas.put(curMetas.getJSONObject(i));
                            }
                        }
                        curPresence.put("metas", keptMetas);

                        if (onLeave != null) {
                            onLeave.onChange(key, curPresence, leftPresence);
                        }

                        // If no metas remain, this presence is fully gone
                        if (keptMetas.length() == 0) {
                            state.remove(key);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in syncDiff", e);
            }
        }

        /**
         * Returns true if the given phx_ref string is found in the metas array.
         */
        private static boolean containsRef(JSONArray metas, String phxRef) {
            for (int i = 0; i < metas.length(); i++) {
                try {
                    if (metas.getJSONObject(i).optString("phx_ref").equals(phxRef)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }
}


/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  USAGE GUIDE & EXAMPLES
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  EXAMPLE 1: Basic socket + channel
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  // Typically in your ViewModel or Application singleton:
 *  PhoenixSocket socket = new PhoenixSocket.Builder("wss://yourserver.com/socket")
 *      .param("token", authToken)     // sent as ?token=... on connect
 *      .timeout(10_000)               // default push timeout: 10s
 *      .heartbeatIntervalMs(30_000)   // send heartbeat every 30s
 *      .logger((tag, msg) -> Log.d("PHX", "[" + tag + "] " + msg))
 *      .build();
 *
 *  // Register socket lifecycle callbacks BEFORE connecting
 *  socket.onOpen(()           -> Log.d(TAG, "opened"));
 *  socket.onClose((code, why) -> Log.d(TAG, "closed " + why));
 *  socket.onError(reason      -> Log.e(TAG, "error " + reason));
 *
 *  socket.connect();
 *
 *  // Channel with auth params
 *  JSONObject chanParams = new JSONObject();
 *  chanParams.put("room_token", roomToken);
 *  PhoenixChannel channel = socket.channel("room:lobby", chanParams);
 *
 *  // Subscribe BEFORE joining so you don't miss early messages
 *  channel.on("new_msg", (payload, ref, joinRef) -> {
 *      String body = payload.optString("body");
 *      runOnUiThread(() -> addMessage(body));
 *  });
 *
 *  // Join
 *  channel.join()
 *      .receive("ok",      (p, r, jr) -> Log.d(TAG, "joined!"))
 *      .receive("error",   (p, r, jr) -> Log.e(TAG, "join error: " + p))
 *      .receive("timeout", (p, r, jr) -> Log.w(TAG, "join timeout"));
 *
 *  // Push a message
 *  JSONObject msg = new JSONObject();
 *  msg.put("body", "Hello, World!");
 *  channel.push("new_msg", msg)
 *      .receive("ok",      (p, r, jr) -> Log.d(TAG, "sent"))
 *      .receive("error",   (p, r, jr) -> Log.e(TAG, "send error"))
 *      .receive("timeout", (p, r, jr) -> Log.w(TAG, "send timeout"));
 *
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  EXAMPLE 2: Android lifecycle integration (the right way)
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  public class ChatActivity extends AppCompatActivity {
 *
 *      private PhoenixSocket socket;
 *      private PhoenixChannel channel;
 *
 *      @Override protected void onCreate(Bundle savedInstanceState) {
 *          super.onCreate(savedInstanceState);
 *          socket = new PhoenixSocket.Builder("wss://yourserver.com/socket")
 *              .param("token", getAuthToken())
 *              .build();
 *          socket.connect();
 *          channel = socket.channel("room:general");
 *          channel.join().receive("ok", (p,r,jr) -> ...);
 *          channel.on("new_msg", (p,r,jr) -> runOnUiThread(() -> show(p)));
 *      }
 *
 *      @Override protected void onStop() {
 *          super.onStop();
 *          socket.onActivityPause();  // disconnects to save battery
 *      }
 *
 *      @Override protected void onStart() {
 *          super.onStart();
 *          socket.onActivityResume(); // reconnects; channels rejoin automatically
 *      }
 *
 *      @Override protected void onDestroy() {
 *          super.onDestroy();
 *          socket.shutdown();         // clean up threads
 *      }
 *  }
 *
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  EXAMPLE 3: Multiple channels on one socket
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  PhoenixChannel roomChannel  = socket.channel("room:general");
 *  PhoenixChannel notifChannel = socket.channel("notifications:" + userId);
 *
 *  roomChannel.join().receive("ok",  (p,r,jr) -> Log.d(TAG, "room joined"));
 *  notifChannel.join().receive("ok", (p,r,jr) -> Log.d(TAG, "notifs joined"));
 *
 *  roomChannel.on("new_msg",    (p,r,jr) -> handleMessage(p));
 *  notifChannel.on("new_notif", (p,r,jr) -> handleNotification(p));
 *
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  EXAMPLE 4: Presence
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  PhoenixChannel channel   = socket.channel("room:lobby");
 *  PhoenixPresence presence = new PhoenixPresence(channel);
 *
 *  presence.onJoin((id, current, newPres) -> {
 *      if (current == null) Log.d(TAG, id + " joined for first time");
 *      else Log.d(TAG, id + " joined from another tab/device");
 *  });
 *  presence.onLeave((id, current, leftPres) -> {
 *      JSONArray metas = current.optJSONArray("metas");
 *      if (metas != null && metas.length() == 0) {
 *          Log.d(TAG, id + " fully left");
 *      }
 *  });
 *  presence.onSync(() -> {
 *      int count = presence.getState().size();
 *      runOnUiThread(() -> userCountView.setText(count + " online"));
 *  });
 *
 *  channel.join();
 *
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  EXAMPLE 5: Unsubscribing a specific listener
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  // Register listener (on() posts to handler; id is approximate for setup-time calls)
 *  channel.on("typing", (p, r, jr) -> showTyping(p));
 *
 *  // Remove ALL listeners for "typing"
 *  channel.off("typing");
 *
 *
 *  ─────────────────────────────────────────────────────────────────────────────
 *  COMMON PITFALLS
 *  ─────────────────────────────────────────────────────────────────────────────
 *
 *  ✗ DON'T update UI directly in callbacks — use runOnUiThread() or postValue()
 *  ✗ DON'T call join() more than once — the channel auto-rejoins
 *  ✗ DON'T create a new socket/channel on every Activity start — reuse them
 *  ✗ DON'T forget to call socket.shutdown() in onDestroy()
 *  ✓ DO call socket.onActivityPause() in onStop() and onActivityResume() in onStart()
 *  ✓ DO register channel.on() listeners BEFORE calling channel.join()
 *  ✓ DO check .receive("error", ...) and .receive("timeout", ...) on every push()
 * ═══════════════════════════════════════════════════════════════════════════════
 */
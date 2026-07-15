import asyncio
import json
import time
import jwt
import websockets
from collections import Counter
import os
import logging

logging.getLogger('asyncio').setLevel(logging.CRITICAL)

BASE_WS = "ws://localhost:4000/socket/websocket?vsn=2.0.0"
TARGET_CONNECTIONS = 10_000
RAMP_UP_SECONDS = 40       
HEARTBEAT_INTERVAL = 25       
JOIN_TOPIC = "flare:home"
JWT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET"

# New detailed metrics
stats = {
    "waiting": TARGET_CONNECTIONS, "connecting": 0, "active": 0, 
    "failed": 0, "dropped": 0, 
    "bytes_sent": 0, "bytes_received": 0
}
latencies = []
error_reasons = Counter()
stats_lock = asyncio.Lock()
start_time = time.time()

def generate_local_token(client_id):
    guest_id = f"guest_loadtest_{client_id}_{int(time.time())}"
    payload = {"sub": guest_id, "exp": int(time.time()) + 3600}
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")

async def run_client(client_id):
    ws = None
    is_active = False
    
    try:
        async with stats_lock:
            stats["waiting"] -= 1
            stats["connecting"] += 1

        token = generate_local_token(client_id)
        
        # 1. Measure Connection Latency
        t0 = time.monotonic()
        ws = await websockets.connect(f"{BASE_WS}&token={token}", ping_interval=None, max_size=None)
        t1 = time.monotonic()
        
        latency_ms = (t1 - t0) * 1000
        async with stats_lock:
            latencies.append(latency_ms)

        # 2. Track Bytes Sent (Join Message)
        join_msg = json.dumps(["1", "1", JOIN_TOPIC, "phx_join", {}])
        await ws.send(join_msg)
        async with stats_lock:
            stats["bytes_sent"] += len(join_msg.encode('utf-8'))
        
        join_successful = False
        
        for _ in range(5):
            msg_raw = await asyncio.wait_for(ws.recv(), timeout=15.0)
            
            # Track Bytes Received (Layouts, ACKs, etc)
            async with stats_lock:
                stats["bytes_received"] += len(msg_raw)
            
            if isinstance(msg_raw, bytes):
                continue
                
            msg = json.loads(msg_raw)
            if len(msg) >= 4 and msg[3] == "phx_reply" and msg[1] == "1":
                if msg[4].get("status") == "ok":
                    join_successful = True
                    break
                else:
                    raise Exception(f"Rejected: {msg[4].get('response')}")

        if not join_successful:
            raise Exception("Never received phx_reply ACK.")

        is_active = True
        async with stats_lock:
            stats["connecting"] -= 1
            stats["active"] += 1

        last_hb = time.monotonic()
        while True:
            now = time.monotonic()
            if now - last_hb >= HEARTBEAT_INTERVAL:
                hb_msg = json.dumps([None, "hb", "phoenix", "heartbeat", {}])
                await ws.send(hb_msg)
                async with stats_lock:
                    stats["bytes_sent"] += len(hb_msg.encode('utf-8'))
                last_hb = now

            try:
                msg_raw = await asyncio.wait_for(ws.recv(), timeout=5)
                async with stats_lock:
                    stats["bytes_received"] += len(msg_raw)
                
                if isinstance(msg_raw, bytes):
                    continue
                    
                msg = json.loads(msg_raw)
                if len(msg) >= 4 and msg[3] in ["phx_error", "phx_close"]:
                    raise Exception(f"Server sent {msg[3]}")
                    
            except asyncio.TimeoutError:
                pass

    except asyncio.CancelledError:
        pass
    except Exception as e:
        error_name = type(e).__name__
        reason = f"{error_name}: {str(e)}"[:60]

        async with stats_lock:
            error_reasons[reason] += 1
            if is_active:
                stats["active"] -= 1
                stats["dropped"] += 1
            else:
                if stats["connecting"] > 0:
                    stats["connecting"] -= 1
                else:
                    stats["waiting"] -= 1
                stats["failed"] += 1
    finally:
        if ws:
            try:
                await ws.close()
            except Exception:
                pass

async def reporter():
    try:
        while True:
            await asyncio.sleep(1)
            elapsed = int(time.time() - start_time)
            print("\033c", end="") 
            print(f"🔥 FLARE LIVE METRICS | Elapsed: {elapsed}s | Target: {TARGET_CONNECTIONS}")
            print("="*65)
            print(f"✅ Active     : {stats['active']} users connected")
            print(f"⏳ Connecting : {stats['connecting']} users handshaking")
            print(f"🕒 Waiting    : {stats['waiting']} in queue")
            print("="*65)
            if error_reasons:
                print("⚠️ Errors encountered (if any):")
                for reason, count in error_reasons.most_common(3):
                    print(f"   - {count}x : {reason}")
    except asyncio.CancelledError:
        pass

def print_final_report():
    elapsed = round(time.time() - start_time, 2)
    avg_latency = round(sum(latencies) / len(latencies), 2) if latencies else 0
    max_latency = round(max(latencies), 2) if latencies else 0
    
    mb_received = round(stats["bytes_received"] / 1024 / 1024, 2)
    mb_sent = round(stats["bytes_sent"] / 1024 / 1024, 2)
    
    avg_data_per_user = round((stats["bytes_received"] + stats["bytes_sent"]) / 1024 / (stats["active"] or 1), 2)

    print("\n\n" + "═"*65)
    print("🚀 FLARE LOAD TEST : FINAL SHOWCASE REPORT")
    print("═"*65)
    print(f"Duration               : {elapsed} seconds")
    print(f"Total Connections      : {stats['active']} out of {TARGET_CONNECTIONS}")
    print(f"Dropped / Failed       : {stats['dropped']} / {stats['failed']}")
    print("-" * 65)
    print(f"Average Connect Time   : {avg_latency} ms")
    print(f"Max Connect Time       : {max_latency} ms")
    print("-" * 65)
    print(f"Total Data Downloaded  : {mb_received} MB")
    print(f"Total Data Uploaded    : {mb_sent} MB")
    print(f"Data usage per client  : ~ {avg_data_per_user} KB per user")
    print("═"*65 + "\n")

async def main():
    reporter_task = asyncio.create_task(reporter())
    tasks = []
    delay = RAMP_UP_SECONDS / TARGET_CONNECTIONS
    
    for i in range(TARGET_CONNECTIONS):
        tasks.append(asyncio.create_task(run_client(i)))
        await asyncio.sleep(delay)

    await asyncio.Event().wait()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print_final_report()
        os._exit(0)
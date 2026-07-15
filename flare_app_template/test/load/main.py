# For in testing in windows 

# launch server lik this 

# $env:ERL_MAX_PORTS="65536"
# iex.bat --erl "+Q 65536" -S mix phx.server

import asyncio
import json
import time
import jwt  
import websockets
from collections import Counter
import sys
import os
import logging

logging.getLogger('asyncio').setLevel(logging.CRITICAL)

BASE_WS = "ws://localhost:4000/socket/websocket?vsn=2.0.0"
TARGET_CONNECTIONS = 10_000
RAMP_UP_SECONDS = 30       
HEARTBEAT_INTERVAL = 25       
JOIN_TOPIC = "flare:home"
JWT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET"

stats = {"waiting": TARGET_CONNECTIONS, "connecting": 0, "active": 0, "failed": 0, "dropped": 0}
error_reasons = Counter()
stats_lock = asyncio.Lock()

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
        ws = await websockets.connect(f"{BASE_WS}&token={token}", ping_interval=None, max_size=None)
        
        await ws.send(json.dumps(["1", "1", JOIN_TOPIC, "phx_join", {}]))
        
        # We need to wait for the phx_reply to confirm the join worked.
        # However, the server might send the binary 'init' layout FIRST before the reply!
        join_successful = False
        
        for _ in range(5): # Check up to 5 incoming messages
            msg_raw = await asyncio.wait_for(ws.recv(), timeout=15.0)
            
            # If it is a binary frame (bytes), it's the compressed layout! Just ignore it for the load test.
            if isinstance(msg_raw, bytes):
                continue
                
            # If it is text, parse it
            msg = json.loads(msg_raw)
            if len(msg) >= 4 and msg[3] == "phx_reply" and msg[1] == "1":
                if msg[4].get("status") == "ok":
                    join_successful = True
                    break
                else:
                    raise Exception(f"Phoenix rejected join: {msg[4].get('response')}")

        if not join_successful:
            raise Exception("Never received phx_reply ACK for the join request.")

        is_active = True
        async with stats_lock:
            stats["connecting"] -= 1
            stats["active"] += 1

        last_hb = time.monotonic()
        while True:
            now = time.monotonic()
            if now - last_hb >= HEARTBEAT_INTERVAL:
                await ws.send(json.dumps([None, "hb", "phoenix", "heartbeat", {}]))
                last_hb = now

            try:
                msg_raw = await asyncio.wait_for(ws.recv(), timeout=5)
                
                # If we get a binary layout_update, ignore it
                if isinstance(msg_raw, bytes):
                    continue
                    
                msg = json.loads(msg_raw)
                if len(msg) >= 4 and msg[3] in ["phx_error", "phx_close"]:
                    raise Exception(f"Server sent {msg[3]}: Channel Crashed!")
                    
            except asyncio.TimeoutError:
                pass

    except asyncio.CancelledError:
        pass
    except Exception as e:
        error_name = type(e).__name__
        error_msg = str(e)
        
        if isinstance(e, asyncio.TimeoutError):
            reason = "Timeout (Server or Database too slow)"
        elif isinstance(e, OSError):
            reason = "OS Limit Reached (Wait 2 mins for Windows TIME_WAIT to clear)"
        elif isinstance(e, websockets.exceptions.ConnectionClosed):
            reason = f"WS Closed unexpectedly (Code: {e.code})"
        else:
            reason = f"{error_name}: {error_msg}"[:60]

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
    start_time = time.time()
    try:
        while True:
            await asyncio.sleep(1)
            elapsed = int(time.time() - start_time)
            print("\033c", end="") 
            print(f"🔥 FLARE LOAD TEST | Elapsed: {elapsed}s | Target: {TARGET_CONNECTIONS}")
            print("="*60)
            print(f"🕒 Waiting    : {stats['waiting']} (In queue to connect)")
            print(f"⏳ Connecting : {stats['connecting']} (Negotiating handshake)")
            print(f"✅ Active     : {stats['active']} (Successfully connected)")
            print(f"❌ Failed     : {stats['failed']} (Failed during setup)")
            print(f"💀 Dropped    : {stats['dropped']} (Lost connection later)")
            print("="*60)
            
            if error_reasons:
                print("⚠️  Top Error Reasons:")
                for reason, count in error_reasons.most_common(5):
                    print(f"   - {count}x : {reason}")
    except asyncio.CancelledError:
        pass

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
        print("\n\nStopped by user (Ctrl+C). Shutting down instantly...")
        os._exit(0)
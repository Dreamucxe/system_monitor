#!/usr/bin/env python3
"""
System Monitor — live CPU / RAM / refresh-rate dashboard.

Reads real stats from Linux /proc and serves a dark web dashboard.
No third-party dependencies. Just run it and open the printed URL.

    python monitor.py
    python monitor.py --port 9000     # custom port
"""

import argparse
import json
import os
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ----------------------------------------------------------------------------
# /proc readers
# ----------------------------------------------------------------------------

# --- CPU measurement -------------------------------------------------------
# Many Android/proot sandboxes freeze /proc/stat (jiffies never advance), so
# the classic delta method reports 0% forever. Instead we run a tiny fixed
# benchmark each tick and measure how long it takes: when the CPU is busy the
# scheduler gives our thread less time, so the same work takes longer. The
# fastest time we've ever seen is the "idle baseline"; how much slower we are
# now maps to a real contention percentage.
_cpu_state = {"baseline": None}


def _bench(n=120000):
    """Fixed CPU workload; returns seconds elapsed (lower = less contention)."""
    t = time.perf_counter()
    x = 0
    for i in range(n):
        x += i * i
    return time.perf_counter() - t


def read_cpu_percent():
    """Real CPU contention %, measured by benchmark slowdown vs. idle baseline."""
    try:
        cur = min(_bench(), _bench())  # best of 2 to shrug off scheduler noise
        base = _cpu_state["baseline"]
        # Auto-calibrate: the fastest run ever seen represents an idle CPU.
        if base is None or cur < base:
            _cpu_state["baseline"] = base = cur
        if cur <= 0:
            return 0.0
        busy = (1.0 - base / cur) * 100.0  # 0% at baseline, →100% as it slows
        return round(max(0.0, min(100.0, busy)), 1)
    except Exception:
        return 0.0


def read_cpu_count():
    """Number of logical CPU cores."""
    try:
        n = 0
        with open("/proc/stat") as f:
            for line in f:
                if line.startswith("cpu") and line[3:4].isdigit():
                    n += 1
        return n or os.cpu_count() or 1
    except Exception:
        return os.cpu_count() or 1


def read_mem():
    """RAM stats in GB plus used percentage, from /proc/meminfo."""
    info = {}
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                key, _, rest = line.partition(":")
                info[key] = int(rest.split()[0])  # value in kB
    except Exception:
        return {"total": 0, "used": 0, "available": 0, "percent": 0.0}

    total_kb = info.get("MemTotal", 0)
    avail_kb = info.get("MemAvailable", info.get("MemFree", 0))
    used_kb = total_kb - avail_kb
    to_gb = lambda kb: round(kb / 1024 / 1024, 2)
    percent = round(100.0 * used_kb / total_kb, 1) if total_kb else 0.0
    return {
        "total": to_gb(total_kb),
        "used": to_gb(used_kb),
        "available": to_gb(avail_kb),
        "percent": percent,
    }


def read_loadavg():
    """1/5/15-minute load averages."""
    try:
        with open("/proc/loadavg") as f:
            a, b, c = f.read().split()[:3]
        return [float(a), float(b), float(c)]
    except Exception:
        return [0.0, 0.0, 0.0]


def read_uptime():
    """System uptime in seconds."""
    try:
        with open("/proc/uptime") as f:
            return float(f.read().split()[0])
    except Exception:
        return 0.0


def snapshot():
    return {
        "cpu": read_cpu_percent(),
        "cores": read_cpu_count(),
        "mem": read_mem(),
        "load": read_loadavg(),
        "uptime": read_uptime(),
        "time": time.strftime("%H:%M:%S"),
    }


# ----------------------------------------------------------------------------
# Web page
# ----------------------------------------------------------------------------

PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>System Monitor</title>
<style>
  :root{
    --bg:#0a0e17; --panel:#121828; --panel2:#0f1420;
    --txt:#e6ecf5; --dim:#7a869e; --line:#232c40;
    --cpu:#4f9dff; --ram:#8b5cf6; --fps:#22d3aa; --warn:#ff5470;
  }
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  html,body{margin:0;height:100%}
  body{
    background:radial-gradient(1200px 600px at 50% -10%,#141d33,var(--bg));
    color:var(--txt);font-family:-apple-system,"Segoe UI",Roboto,system-ui,sans-serif;
    padding:18px 16px 40px;min-height:100%;
  }
  header{display:flex;align-items:baseline;justify-content:space-between;margin-bottom:18px}
  h1{font-size:18px;font-weight:700;margin:0;letter-spacing:.3px}
  h1 span{color:var(--cpu)}
  .clock{color:var(--dim);font-variant-numeric:tabular-nums;font-size:13px}
  .grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
  .card{
    background:linear-gradient(180deg,var(--panel),var(--panel2));
    border:1px solid var(--line);border-radius:18px;padding:16px;
    box-shadow:0 8px 30px rgba(0,0,0,.35);position:relative;overflow:hidden;
  }
  .card.wide{grid-column:1 / -1}
  .label{font-size:12px;color:var(--dim);text-transform:uppercase;letter-spacing:1px;margin-bottom:4px}
  .big{font-size:34px;font-weight:800;font-variant-numeric:tabular-nums;line-height:1}
  .unit{font-size:14px;color:var(--dim);font-weight:600;margin-left:4px}
  .sub{font-size:12px;color:var(--dim);margin-top:6px}
  /* radial gauge */
  .gauge{display:flex;align-items:center;gap:14px}
  .ring{--v:0;--c:var(--cpu);width:78px;height:78px;border-radius:50%;flex:0 0 78px;
    background:conic-gradient(var(--c) calc(var(--v)*1%), #1c2438 0);
    display:grid;place-items:center;transition:--v .4s ease;position:relative}
  .ring::before{content:"";position:absolute;inset:8px;border-radius:50%;background:var(--panel)}
  .ring b{position:relative;font-size:16px;font-weight:800;font-variant-numeric:tabular-nums}
  @property --v{syntax:'<number>';inherits:false;initial-value:0}
  /* bar */
  .bar{height:10px;background:#1c2438;border-radius:6px;overflow:hidden;margin-top:12px}
  .bar>i{display:block;height:100%;width:0;border-radius:6px;transition:width .4s ease}
  .row{display:flex;justify-content:space-between;font-size:12px;color:var(--dim);margin-top:6px}
  .spark{width:100%;height:46px;display:block;margin-top:10px}
  .loads{display:flex;gap:18px;margin-top:6px}
  .loads div b{font-size:20px;font-weight:800}
  .loads div span{font-size:11px;color:var(--dim);display:block}
  footer{text-align:center;color:var(--dim);font-size:11px;margin-top:20px}
  .dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--fps);margin-right:6px;
    box-shadow:0 0 8px var(--fps);animation:pulse 1.5s infinite}
  @keyframes pulse{50%{opacity:.35}}
</style>
</head>
<body>
  <header>
    <h1>System<span>Monitor</span></h1>
    <div class="clock"><span class="dot"></span><span id="clock">--:--:--</span></div>
  </header>

  <div class="grid">
    <div class="card">
      <div class="label">CPU Usage</div>
      <div class="gauge">
        <div class="ring" id="cpuRing" style="--c:var(--cpu)"><b id="cpuNum">0%</b></div>
        <div>
          <div class="big"><span id="cpuBig">0</span><span class="unit">%</span></div>
          <div class="sub" id="cpuCores">— cores</div>
        </div>
      </div>
      <svg class="spark" id="cpuSpark" viewBox="0 0 100 40" preserveAspectRatio="none">
        <polyline fill="none" stroke="var(--cpu)" stroke-width="1.6" points=""/>
      </svg>
    </div>

    <div class="card">
      <div class="label">RAM Usage</div>
      <div class="gauge">
        <div class="ring" id="ramRing" style="--c:var(--ram)"><b id="ramNum">0%</b></div>
        <div>
          <div class="big"><span id="ramUsed">0</span><span class="unit">GB</span></div>
          <div class="sub"><span id="ramTotal">0</span> GB total</div>
        </div>
      </div>
      <div class="bar"><i id="ramBar" style="background:var(--ram)"></i></div>
      <div class="row"><span id="ramAvail">— free</span><span id="ramPct">0%</span></div>
    </div>

    <div class="card">
      <div class="label">Refresh Rate</div>
      <div class="big"><span id="fps">--</span><span class="unit">Hz</span></div>
      <div class="sub">display / browser FPS</div>
      <svg class="spark" id="fpsSpark" viewBox="0 0 100 40" preserveAspectRatio="none">
        <polyline fill="none" stroke="var(--fps)" stroke-width="1.6" points=""/>
      </svg>
    </div>

    <div class="card">
      <div class="label">Load Average</div>
      <div class="loads">
        <div><b id="l1">0.0</b><span>1 min</span></div>
        <div><b id="l5">0.0</b><span>5 min</span></div>
        <div><b id="l15">0.0</b><span>15 min</span></div>
      </div>
      <div class="sub" id="uptime" style="margin-top:14px">uptime —</div>
    </div>
  </div>

  <footer id="status">connecting…</footer>

<script>
// ---- refresh rate (measured in the browser) ----
let frames=0, lastT=performance.now(), fpsVal=0;
const fpsHist=[];
function loop(t){
  frames++;
  if(t-lastT>=500){
    fpsVal=Math.round(frames*1000/(t-lastT));
    frames=0; lastT=t;
    document.getElementById('fps').textContent=fpsVal;
    pushSpark(fpsHist,'fpsSpark',fpsVal,0,Math.max(144,fpsVal));
  }
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

function pushSpark(hist,id,val,min,max){
  hist.push(val); if(hist.length>60)hist.shift();
  const span=Math.max(1,max-min);
  const pts=hist.map((v,i)=>{
    const x=(i/(59))*100;
    const y=40-((v-min)/span)*38-1;
    return x.toFixed(1)+','+y.toFixed(1);
  }).join(' ');
  document.querySelector('#'+id+' polyline').setAttribute('points',pts);
}

function ring(id,numId,pct,warn){
  const r=document.getElementById(id);
  r.style.setProperty('--v',pct);
  r.style.setProperty('--c',pct>=(warn||88)?'var(--warn)':getComputedStyle(r).getPropertyValue('--c-base')||r.dataset.c);
  document.getElementById(numId).textContent=Math.round(pct)+'%';
}

const cpuHist=[];
async function poll(){
  try{
    const r=await fetch('/stats',{cache:'no-store'});
    const d=await r.json();
    // CPU
    document.getElementById('cpuBig').textContent=d.cpu;
    document.getElementById('cpuNum').textContent=Math.round(d.cpu)+'%';
    document.getElementById('cpuCores').textContent=d.cores+' cores';
    setRing('cpuRing','var(--cpu)',d.cpu);
    document.getElementById('cpuNum').textContent=Math.round(d.cpu)+'%';
    pushSpark(cpuHist,'cpuSpark',d.cpu,0,100);
    // RAM
    document.getElementById('ramUsed').textContent=d.mem.used;
    document.getElementById('ramTotal').textContent=d.mem.total;
    document.getElementById('ramAvail').textContent=d.mem.available+' GB free';
    document.getElementById('ramPct').textContent=d.mem.percent+'%';
    document.getElementById('ramNum').textContent=Math.round(d.mem.percent)+'%';
    document.getElementById('ramBar').style.width=d.mem.percent+'%';
    setRing('ramRing','var(--ram)',d.mem.percent);
    document.getElementById('ramNum').textContent=Math.round(d.mem.percent)+'%';
    // load
    document.getElementById('l1').textContent=d.load[0].toFixed(2);
    document.getElementById('l5').textContent=d.load[1].toFixed(2);
    document.getElementById('l15').textContent=d.load[2].toFixed(2);
    // uptime
    let u=Math.floor(d.uptime), h=Math.floor(u/3600), m=Math.floor((u%3600)/60);
    document.getElementById('uptime').textContent='uptime '+h+'h '+m+'m';
    document.getElementById('clock').textContent=d.time;
    document.getElementById('status').textContent='● live — updating every second';
  }catch(e){
    document.getElementById('status').textContent='⚠ lost connection — is monitor.py running?';
  }
}
function setRing(id,color,pct){
  const r=document.getElementById(id);
  const warn=pct>=88;
  r.style.setProperty('--c',warn?'var(--warn)':color);
  r.style.setProperty('--v',pct);
}
poll(); setInterval(poll,1000);
</script>
</body>
</html>
"""


# ----------------------------------------------------------------------------
# HTTP server
# ----------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # keep the terminal quiet

    def do_GET(self):
        if self.path.startswith("/stats"):
            body = json.dumps(snapshot()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path in ("/", "/index.html"):
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)


def local_ip():
    """Best-effort LAN IP so you can open it from another device."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main():
    ap = argparse.ArgumentParser(description="Live CPU / RAM / refresh-rate monitor")
    ap.add_argument("--port", type=int, default=8777)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()

    read_cpu_percent()  # prime the CPU delta baseline

    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    ip = local_ip()
    print("\n  System Monitor is running.\n")
    print(f"    On this device :  http://localhost:{args.port}")
    print(f"    On your network:  http://{ip}:{args.port}")
    print("\n  Press Ctrl+C to stop.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n  Stopped.\n")
        srv.shutdown()


if __name__ == "__main__":
    main()

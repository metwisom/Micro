// Plays a stream of Float32 mono frames (480 samples) with an ADAPTIVE jitter buffer:
// the prefill target grows after underruns (more resilient on jittery networks) and is
// trimmed back down when the buffer stays comfortably full (lower latency on good ones).
class PcmPlayer extends AudioWorkletProcessor {
  constructor(opts) {
    super();
    const o = (opts && opts.processorOptions) || {};
    this.min = Math.max(1, o.prefill || 3);
    this.max = Math.max(this.min + 6, 24);
    this.target = this.min;
    this.queue = [];
    this.cur = null;
    this.pos = 0;
    this.started = false;
    this.fullStreak = 0;
    this.port.onmessage = (e) => {
      this.queue.push(e.data);
      if (this.queue.length > 100) this.queue.shift(); // hard cap ~2 s
    };
  }

  process(_inputs, outputs) {
    const out = outputs[0][0];
    if (!out) return true;

    if (!this.started) {
      if (this.queue.length < this.target) {
        out.fill(0);
        return true;
      }
      this.started = true;
    }

    // Adapt: if the buffer stays well above target, lower latency; trim a frame occasionally.
    if (this.queue.length > this.target + 4) {
      if (++this.fullStreak > 200) {
        this.queue.shift();
        if (this.target > this.min) this.target--;
        this.fullStreak = 0;
      }
    } else {
      this.fullStreak = 0;
    }

    for (let i = 0; i < out.length; i++) {
      if (!this.cur || this.pos >= this.cur.length) {
        this.cur = this.queue.shift() || null;
        this.pos = 0;
      }
      if (!this.cur) {
        out[i] = 0;
        this.started = false; // underrun → re-buffer, and grow the target
        if (this.target < this.max) this.target++;
        this.fullStreak = 0;
      } else {
        out[i] = this.cur[this.pos++];
      }
    }
    return true;
  }
}

registerProcessor("pcm-player", PcmPlayer);

class Capture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.hop = new Float32Array(1024);
    this.n = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) {
      return true;
    }
    let i = 0;
    while (i < ch.length) {
      const take = Math.min(1024 - this.n, ch.length - i);
      this.hop.set(ch.subarray(i, i + take), this.n);
      this.n += take;
      i += take;
      if (this.n === 1024) {
        this.port.postMessage(this.hop.slice());
        this.n = 0;
      }
    }
    return true;
  }
}

registerProcessor("capture", Capture);

// One authorized user, one device, one task. Transport-independent dev controller.
export class ChannelController {
  constructor({ userId, claim, run, stop, reply, now = Date.now }) {
    Object.assign(this, { userId, claim, run, stop, reply, now });
    this.busy = false;
    this.pending = Promise.resolve();
  }

  async receive(event) {
    const { sender, message } = event ?? {};
    if (sender?.sender_type !== "user" || sender.sender_id?.open_id !== this.userId) return;
    if (message?.chat_type !== "p2p" || message.message_type !== "text") return;
    const age = this.now() - Number(message.create_time);
    if (!Number.isFinite(age) || age < -30_000 || age > 120_000 || !message.message_id) return;
    let text;
    try { text = JSON.parse(message.content).text?.trim(); } catch { return; }
    if (!text || !this.claim(message.message_id)) return;
    const reply = (value) => this.reply(message.message_id, value);
    if (["状态", "/status"].includes(text)) {
      await reply(this.busy ? "模拟器任务执行中。" : "开发入口空闲；发送“查看时钟”开始测试。");
      return;
    }
    if (["停止", "/stop"].includes(text)) {
      await this.stop();
      await reply("已请求模拟器停止任务；不自动重试。");
      return;
    }
    if (!["查看时钟", "/clock"].includes(text)) {
      await reply("当前仅支持：查看时钟、状态、停止。这是模拟器开发入口。");
      return;
    }
    if (this.busy) {
      await reply("已有任务执行中，请等待或发送“停止”。");
      return;
    }
    this.busy = true;
    // Return promptly so the platform event handler does not wait for a model run.
    this.pending = (async () => {
      try {
        await reply("已接收，正在模拟器中打开时钟并读取页面。");
        await reply(await this.run());
      } catch {
        await this.stop().catch(() => {});
        await reply("测试未通过，已请求停止。请查看本机诊断日志；不自动重试。").catch(() => {});
      } finally { this.busy = false; }
    })();
  }
}

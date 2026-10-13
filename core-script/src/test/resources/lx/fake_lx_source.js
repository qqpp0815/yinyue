/**
 * @name 冒烟测试源
 * @description QuickJS 桥冒烟测试用的最小洛雪格式脚本
 * @author yinyue
 * @version 1.0.0
 */
lx.on(lx.EVENT_NAMES.request, async function ({ source, action, info }) {
  if (action === 'musicUrl' && source === 'kw') {
    const md5v = lx.utils.crypto.md5('abc');
    const r = await lx.request('https://www.baidu.com', { method: 'GET', timeout: 5000 }, null);
    if (r.err) throw new Error('http fail: ' + r.err);
    return 'https://example.com/audio/' + info.type + '.mp3?md5=' + md5v + '&status=' + r.resp.statusCode;
  }
});
lx.send(lx.EVENT_NAMES.inited, {
  sources: {
    kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] }
  }
});

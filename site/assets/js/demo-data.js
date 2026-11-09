/**
 * Sample content for the "what a brief looks like" section.
 *
 * Three styles, two languages. Values are static, project-authored markup, not
 * user input; the renderer still runs them through a template.
 */

const EN = {
  brief: {
    html: `
      <h3>The short version</h3>
      <p>Local-first software keeps the authoritative copy of your data on your device and treats the network as an optimisation. That is the whole idea — everything else is engineering.</p>
      <ul>
        <li><strong>What you get:</strong> it works offline, and it keeps working <span class="cite">01:12.4s</span></li>
        <li><strong>What it costs:</strong> sync and conflict rules become yours <span class="cite">00:41.0s</span></li>
        <li><strong>Why bother:</strong> the browser tool survived one pricing change <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
  standard: {
    html: `
      <h3>Local-first means the device owns the data</h3>
      <p>The talk defines local-first software as software where the primary copy of your data lives on your own device, and the network is only an optimisation.</p>
      <ul>
        <li><strong>What it buys you:</strong> the app keeps working offline <span class="cite">01:12.4s</span></li>
        <li><strong>What it costs:</strong> sync, conflicts and merge rules are now your problem <span class="cite">00:41.0s</span></li>
        <li><strong>Why it matters:</strong> a browser tool survived exactly one pricing change <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
  study: {
    html: `
      <h3>Lecture notes</h3>
      <h4>Definition</h4>
      <p>Local-first software stores the primary copy of the data on the device that produced it. The server becomes a sync target, not the source of truth.</p>
      <h4>Key terms</h4>
      <ul>
        <li><strong>Local-first</strong> — the working copy is local and stays readable without a connection <span class="cite">00:19.0s</span></li>
        <li><strong>Sync engine</strong> — reconciles two copies that drifted, without asking the user which one wins <span class="cite">00:41.0s</span></li>
      </ul>
      <h4>Takeaways</h4>
      <ul>
        <li>Offline is a feature, not a fallback <span class="cite">01:12.4s</span></li>
        <li>Owning the file is the difference between a tool and a rental <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
};

const ZH = {
  brief: {
    html: `
      <h3>一句话版本</h3>
      <p>本地优先的软件把数据的权威副本留在你自己的设备上，只把网络当成一种优化。核心就这一句，其余都是工程问题。</p>
      <ul>
        <li><strong>你得到什么：</strong>断网也能用，而且十年后还能用 <span class="cite">01:12.4s</span></li>
        <li><strong>代价是什么：</strong>同步和冲突规则要你自己负责 <span class="cite">00:41.0s</span></li>
        <li><strong>为什么值得：</strong>那个浏览器工具只熬过了一次涨价 <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
  standard: {
    html: `
      <h3>本地优先意味着设备拥有数据</h3>
      <p>演讲把本地优先软件定义为：数据的主要副本存放在你自己的设备上，网络只是一种优化手段。</p>
      <ul>
        <li><strong>换来的好处：</strong>离线时应用照常工作 <span class="cite">01:12.4s</span></li>
        <li><strong>付出的代价：</strong>同步、冲突与合并规则都归你处理 <span class="cite">00:41.0s</span></li>
        <li><strong>为什么重要：</strong>浏览器里的工具只经历了一次涨价就消失了 <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
  study: {
    html: `
      <h3>学习笔记</h3>
      <h4>定义</h4>
      <p>本地优先软件把数据的主要副本存放在产生它的设备上，服务器退化为同步目标，而不是唯一事实来源。</p>
      <h4>关键概念</h4>
      <ul>
        <li><strong>本地优先</strong>——工作副本在本地，没有网络也能读取 <span class="cite">00:19.0s</span></li>
        <li><strong>同步引擎</strong>——把两份已经分叉的数据合并，而不必询问用户该信谁 <span class="cite">00:41.0s</span></li>
      </ul>
      <h4>要点</h4>
      <ul>
        <li>离线是功能，不是降级方案 <span class="cite">01:12.4s</span></li>
        <li>文件属于你，才是工具；否则只是租用 <span class="cite">01:38.2s</span></li>
      </ul>`,
  },
};

export const DEMO = { en: EN, zh: ZH };

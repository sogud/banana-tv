<script setup>
import { ref } from 'vue'
import { withBase } from 'vitepress'

const scenes = [
  { name: '青翠山谷', image: '/home-valley.webp', alt: '青翠山谷静态插画上的 Banana TV 常用双排首页' },
  { name: '富士暮色', image: '/home-fuji.webp', alt: '富士山静态照片上的 Banana TV 常用双排首页' }
]
const selected = ref(0)
</script>

<template>
  <main class="banana-home">
    <section class="home-intro">
      <div class="intro-copy">
        <h1>你的电视。<br>你的主屏幕。</h1>
        <div class="intro-details">
          <p>从喜欢的壁纸开始，<br>把应用、影视与直播放在一起。</p>
          <div class="home-actions">
            <a class="primary-link" :href="withBase('/quick-start')">阅读安装指南</a>
            <a class="quiet-link" :href="withBase('/features')">查看功能</a>
          </div>
          <span class="compatibility">开发预览 · Android 9 及以上</span>
        </div>
      </div>
      <figure class="screen-preview">
        <div class="screen-image">
          <img :src="withBase(scenes[selected].image)" :alt="scenes[selected].alt" width="1920" height="1080" fetchpriority="high">
        </div>
        <figcaption class="screen-caption">
          <p>开发版实际界面<span>Android TV 模拟器 · 1920 × 1080</span></p>
          <div class="scene-options" role="group" aria-label="预览壁纸">
            <button v-for="(scene, index) in scenes" :key="scene.name" :aria-pressed="selected === index" @click="selected = index">{{ scene.name }}</button>
          </div>
        </figcaption>
      </figure>
    </section>

    <section class="home-details">
      <article class="detail-row">
        <div class="detail-copy">
          <h2>壁纸，铺满整个屏幕。</h2>
          <p>选择风景、插画或渐变，也可以用手机扫码上传自己的图片。静态壁纸离线显示，不需要视频解码。</p>
          <a class="quiet-link" :href="withBase('/features#壁纸与屏保')">选择壁纸</a>
        </div>
        <figure><img :src="withBase('/wallpapers.webp')" alt="Banana TV 壁纸图库，包含风景与 4K 插画，青翠山谷已选中" width="1920" height="1080" loading="lazy"><figcaption>设置 → 壁纸</figcaption></figure>
      </article>
      <article class="detail-row reverse">
        <div class="detail-copy">
          <h2>常用操作，留在手边。</h2>
          <p>从控制中心进入设置，调整外观、屏保和应用。单排 Dock、常用双排或网格，按你的习惯选择。</p>
          <a class="quiet-link" :href="withBase('/features#主屏幕')">整理主屏幕</a>
        </div>
        <figure><img :src="withBase('/settings.webp')" alt="Banana TV 设置侧栏，背景保持青翠山谷壁纸与双排应用布局" width="1920" height="1080" loading="lazy"><figcaption>控制中心 → 设置</figcaption></figure>
      </article>
    </section>

    <section class="home-story">
      <div><h2>桌面与影视，<br>在同一个地方。</h2><p>用遥控器看，用手机整理。内容与配置保存在电视本机。</p></div>
      <div class="story-details">
        <article><h3>打开就是最近播放</h3><p>从上次的位置继续，选集、换线、音轨和字幕在播放器内完成。</p></article>
        <article><h3>主屏幕，由你安排</h3><p>调整入口大小、创建文件夹、隐藏应用，也可以固定常用 HDMI 设备。</p></article>
        <article><h3>订阅由你配置</h3><p>扫码填写订阅，或用本地配置文件打包。未配置时保持空白。</p><a class="quiet-link" :href="withBase('/subscriptions#手机扫码接入')">接入影视源</a></article>
        <article><h3>按设备选择效果</h3><p>日间与夜间主题、流畅模式，以及可选的照片、时钟和航拍屏保。</p></article>
      </div>
    </section>

    <section class="home-get-started"><h2>在你的电视上试一试。</h2><p>先安装，再根据设备设置 Home。安装指南包含恢复方法。</p><a class="primary-link" :href="withBase('/quick-start')">阅读安装指南</a><a class="quiet-link" :href="withBase('/subscriptions')">配置订阅</a></section>
    <p class="screenshot-credits">截图来自开发版模拟器；应用列表取决于设备安装情况。壁纸：David Revoy，CC BY 4.0；富士暮色：Romain Guy，CC0。<a href="https://github.com/sogud/banana-tv/blob/main/WALLPAPERS.md">图片来源与许可</a>。截图不代表公开稳定版。直播可用性取决于设备与订阅源，选源、选集等操作仍在改进。</p>
  </main>
</template>

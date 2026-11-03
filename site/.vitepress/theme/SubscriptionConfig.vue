<script setup>
import { computed, ref } from 'vue'
const media = ref('')
const playlists = ref('')
const guide = ref('')
const message = ref('')
const config = computed(() => {
  const urls = []
  for (const line of playlists.value.split(/\r?\n/)) {
    const value = line.trim()
    if (value && !urls.includes(value)) urls.push(value)
  }
  return { media: { subscription: media.value.trim() }, live: { subscriptions: urls, epg: guide.value.trim() } }
})
function download() {
  message.value = ''
  const value = config.value
  if (value.live.subscriptions.length > 6) { message.value = '直播订阅最多六个地址。'; return }
  for (const address of [value.media.subscription, value.live.epg, ...value.live.subscriptions]) {
    if (!address) continue
    try {
      const url = new URL(address)
      if (!['http:', 'https:'].includes(url.protocol) || address.length > 4096) throw new Error()
    } catch { message.value = '请填写有效的 HTTP(S) 地址。'; return }
  }
  if (value.live.subscriptions.join('\n').length > 8192) { message.value = '直播地址合计不能超过 8192 个字符。'; return }
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = 'banana-tv.config.json'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  message.value = '已生成。放到项目根目录后重新打包即可。'
}
</script>

<template>
  <form class="subscription-builder" @submit.prevent="download">
    <p class="builder-note">只在当前浏览器生成文件，地址不会上传。</p>
    <label>影视订阅<input v-model="media" type="url" placeholder="可留空" autocomplete="off" spellcheck="false"></label>
    <label>直播订阅<textarea v-model="playlists" rows="4" placeholder="每行一个 M3U 或 TVBox TXT 地址，最多六个" autocomplete="off" spellcheck="false"></textarea></label>
    <label>节目单<input v-model="guide" type="url" placeholder="可选 XMLTV / XMLTV.gz 地址" autocomplete="off" spellcheck="false"></label>
    <details><summary>预览配置</summary><pre>{{ JSON.stringify(config, null, 2) }}</pre></details>
    <button type="submit" class="primary-link">下载配置文件</button>
    <p class="builder-message" role="status">{{ message }}</p>
  </form>
</template>

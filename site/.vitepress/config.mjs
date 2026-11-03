import { defineConfig } from 'vitepress'
import { readdirSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '../..')
const base = process.env.SITE_BASE || '/'
const guides = [
  { text: '快速开始', link: '/quick-start' },
  { text: '功能与使用', link: '/features' },
  { text: '安装与恢复', link: '/install' },
  { text: '影视源接入与打包', link: '/subscriptions' },
  { text: '影视与直播', link: '/media-center' },
  { text: '维护自己的源', link: '/source-catalog' },
  { text: '壁纸浏览', link: '/wallpaper-peek' },
  { text: '应用更新', link: '/updates' },
  { text: 'Aerial 功能对照', link: '/comparison' }
]
const published = new Set(['index.md'])
for (const page of guides) published.add(page.link.slice(1) + '.md')
const excluded = []
function excludePrivateDocs(directory, prefix = '') {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = prefix + entry.name
    if (entry.isDirectory()) excludePrivateDocs(resolve(directory, entry.name), path + '/')
    else if (path.endsWith('.md') && !published.has(path)) excluded.push(path)
  }
}
excludePrivateDocs(resolve(root, 'docs'))

export default defineConfig({
  base,
  title: 'Banana TV',
  description: '原生 Android TV 桌面与影视。安装、订阅和使用指南。',
  lang: 'zh-CN',
  srcDir: '../docs',
  srcExclude: excluded,
  outDir: './dist',
  cacheDir: './.cache',
  cleanUrls: true,
  head: [['link', { rel: 'icon', type: 'image/png', href: base + 'banana.png' }]],
  themeConfig: {
    logo: '/banana.png',
    nav: [{ text: '使用指南', link: '/quick-start' }, { text: '影视源接入', link: '/subscriptions' }, { text: '功能', link: '/features' }],
    sidebar: [{ text: 'Banana TV', items: guides }],
    outline: { label: '本页内容', level: [2, 3] },
    search: { provider: 'local', options: { locales: { root: { translations: {
      button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
      modal: { noResultsText: '没有找到相关内容', resetButtonTitle: '清空', footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' } }
    } } } } },
    socialLinks: [{ icon: 'github', link: 'https://github.com/sogud/banana-tv' }],
    docFooter: { prev: '上一页', next: '下一页' },
    sidebarMenuLabel: '目录', returnToTopLabel: '返回顶部', darkModeSwitchLabel: '切换主题',
    footer: { message: 'Android TV 桌面与影视。订阅由你掌握。', copyright: `Banana TV · <a href="${base}FONT-LICENSE-OFL.txt">Outfit / SIL OFL 1.1</a>` }
  },
  markdown: {
    config(md) {
      const render = md.renderer.rules.link_open || ((tokens, index, options, env, self) => self.renderToken(tokens, index, options))
      md.renderer.rules.link_open = (tokens, index, options, env, self) => {
        const href = tokens[index].attrGet('href')
        if (href?.startsWith('../')) tokens[index].attrSet('href', 'https://github.com/sogud/banana-tv/blob/main/' + href.slice(3))
        return render(tokens, index, options, env, self)
      }
    }
  },
  vite: {
    resolve: {
      alias: [
        { find: 'vue/server-renderer', replacement: resolve(root, 'site/node_modules/vue/server-renderer/index.mjs') },
        { find: /^vue$/, replacement: resolve(root, 'site/node_modules/vue/dist/vue.runtime.esm-bundler.js') }
      ]
    },
    server: { fs: { allow: [resolve(root, 'docs'), resolve(root, 'site')] } }
  }
})

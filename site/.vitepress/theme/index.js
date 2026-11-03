import DefaultTheme from 'vitepress/theme'
import Home from './Home.vue'
import SubscriptionConfig from './SubscriptionConfig.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('BananaHome', Home)
    app.component('SubscriptionConfig', SubscriptionConfig)
  }
}

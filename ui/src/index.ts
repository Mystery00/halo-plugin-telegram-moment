import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import HomeView from './views/HomeView.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/telegram-moment',
        name: 'TelegramMoment',
        component: HomeView,
        meta: {
          title: 'Telegram 瞬间同步',
          searchable: true,
          permissions: ['plugin:telegram-moment:manage'],
          menu: {
            name: 'Telegram 瞬间同步',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
})

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
          title: 'Telegram Moment',
          searchable: true,
          menu: {
            name: 'Telegram Moment',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
})

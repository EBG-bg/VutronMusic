# @vutronmusic/ui

桌面与 Android 实际共用的 Vue 控件。当前不包含平台 API、路由或 Pinia Store。

- `ButtonIcon.vue` 从原桌面同名组件抽取，保留 slot、属性透传、click MouseEvent、preventBlur、背景过渡及按压行为；原路径重导出同一个组件。
- `SideNavItem.vue` 从原 SideNav 提取导航按钮状态，桌面原侧栏与 Android 底部导航共用；desktop/mobile 仅区分布局。

桌面导航业务、tooltip 与平台能力仍由调用方提供。Android 原生触摸反馈通过 data 属性补齐，不在此复制一套导航业务。

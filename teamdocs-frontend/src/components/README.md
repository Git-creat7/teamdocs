# 组件约定

## 弹层组件（Dialog / Drawer）通信惯例

**数据归属页面，弹层默认受控。**

1. 默认形态：数据由父页面拉取并通过 props 传入，变更通过 emit 通知父级，由父级更新数据源。示例：`MembersDrawer`（props: members/myRole，emit: refresh）、`DocumentTagsPopover`（props: allTags/attachedTagNames，emit: changed 带变更详情）。
2. 弹层要自治（自己发请求）必须有理由——典型的合法理由是"该数据只有弹层关心，页面不消费"。示例：`DocumentDetailPanel` 的评论（页面不展示评论，自拉合理）；它的标签则受控（页面行内 chips 也在用）。
3. 所有弹层必须 `append-to-body`，避免被页面内层叠上下文吞掉遮罩。
4. emit 事件带足变更详情（如 `{ docId, tag, added }`），让父级能做本地增量更新，而不是只能全量重拉。

## 响应解包

`Array.isArray(res?.records) ? res.records : []` 这类防御性解包属于 API 层，
统一在 `src/api/*.js` 用 `utils/normalize.js` 的 `asList` / `asPage` 处理。
组件层拿到的永远是规整结构，新代码不要在组件里重复解包。

## 实体配色

跟随实体本身、不跟列表下标：空间色用 `utils/spaceColors.js`（按 space.id 哈希），
标签色用 `utils/tagColors.js`（按标签名哈希）。不要再写 `colors[index % n]`。

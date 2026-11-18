# LumaMenu

LumaMenu 是 Paper/Leaf 26.3 的菜单引擎：画面用只发给本人的展示实体（TextDisplay、ItemDisplay）绘制，点击和悬停交给原版 Dialog 的透明正文处理。玩家无需安装客户端模组，只需接收插件导出的资源包。

## 特点

- **画面**：文字、矩形、边框、线、坐标网格、按钮、分组，以及任意物品或方块模型（含自定义 `item_model`）。所有节点都支持 XYZ 旋转和拉伸，父组的变换会作用到子节点。
- **点击**：个人两点定位校准，按实际变换算出点击轮廓，最小命中粒度 1 单位。
- **跟随**：展示实体只对本人发包，骑乘在玩家身上，由客户端跟随镜头；服务器不发位置包。
- **两种显示模式**：兼容模式保留光影；增强模式为正交投影、去掉方向暗面，使用时需关闭光影。兼容模式的页面可选“低头”，用来消除模型暗面。
- **动作**：`open`、`back`、`close`、`return`、`tell`、`sound`、`command`（先关菜单）、`command-in-place`、`console`、`callback`、`calibrate`；支持单击/双击仲裁（300ms），以及页面的打开/关闭事件。
- **文字**：`&`/`§` 颜色与装饰、`&#RRGGBB`、左中右对齐、按 26.3 默认字体的实际字宽自动换行（`line-width` + `max-lines`）、半透明。
- **编译缓存**：`/lumamenu reload` 编译并保存布局和点击缓存，打开菜单时不再重新计算。

## 运行环境

- Paper 或 Leaf 26.3、Java 25
- PacketEvents 2.14.0
- 26.3 客户端，并接收插件导出的资源包（`plugins/LumaMenu/resourcepack/`），需要合并进服务器资源包后发布

## 命令

| 命令 | 说明 |
| --- | --- |
| `/lumamenu open [页面]` | 打开页面；尚未校准时会先进入定位校准 |
| `/lumamenu calibrate` | 两点定位校准（更换 FOV、GUI 缩放或窗口大小后需要重新校准） |
| `/lumamenu mode [compatible\|enhanced]` | 切换个人显示模式 |
| `/lumamenu list` | 按布局文件列出页面 |
| `/lumamenu reload [文件]` | 重新编译全部布局，或只重读指定文件（需 `lumamenu.admin`） |
| `/lumamenu status`、`/lumamenu export` | 查看状态；导出资源包（需 `lumamenu.admin`） |

示例布局在 `src/main/resources/layouts/`；`features.yml` 演示动作、手势、事件和文字排版。

## Java API

通过 Bukkit `ServicesManager` 获取 `dev.lumamenu.LumaMenuApi`，所有调用和回调都在服务器主线程执行。主要方法有：`open(owner, player, page, values, callback, closed)`、`page(player)`、`refresh(player)`、`close(player)`、`mode(player[, mode])`，以及 `Handle.update(values)` 和 `Handle.open/back`。会话结束时会触发 `LumaMenuClosedEvent`。

## 构建

```
export LUMAMENU_LOCAL_CLASSPATH=<Paper/Leaf 26.3 API 及其依赖，以路径分隔符连接>
export LUMAMENU_PACKETEVENTS_JAR=<packetevents-spigot-2.14.0.jar>
./gradlew build
```

如需运行离线原生检查（`./gradlew nativeHitChecks`），以及重新导出字宽表（`./gradlew fontWidths`），还要设置：

- `LUMAMENU_CLIENT_CLASSPATH`：26.3 客户端 jar 及其依赖库。
- `LUMAMENU_CLIENT_JAR`：26.3 客户端 jar。
- `LUMAMENU_ASSETS_INDEX`：26.3 资源索引 JSON，同级目录结构里需要带有对应的 `objects/` 目录。

## 许可

Apache License 2.0，附加 Commons Clause 条件（不得出售本软件或主要以其功能为价值的服务），与 DialogMenu 相同。部分代码改编自 DialogMenu，详见 `LICENSE` 与 `NOTICE`。

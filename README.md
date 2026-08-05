# 🔥 火炬之光无限 - 自动助手

一款专为《火炬之光无限》S13赛季设计的自动化辅助工具。

## ✨ 功能特性

### 1. 📊 实时日志监听
- 监听游戏日志文件 `UE_game.log`
- 实时显示物品掉落（传奇/稀有/魔法/普通）
- 统计金币获取数量
- 物品分类统计

### 2. 🎬 脚本录制/回放
- **录制功能**：记录点击、滑动、长按等操作
- **精确回放**：100%还原操作时序
- **坐标自适应**：自动缩放适配不同分辨率
- **脚本管理**：保存/加载/删除脚本

### 3. 🤖 智能自动化
- **自动刷副本**：检测副本状态，自动战斗
- **背包满处理**：检测到背包满 → 自动回仓库存装备 → 返回副本
- **断线重连**：检测断线 → 自动重连 → 重新登录
- **异常恢复**：网络波动、卡死等情况自动处理

## 📱 安装指南

### 前置要求
- Android 14 设备
- Shizuku 已激活（推荐）或 Root 权限
- Termux 环境（用于打包）

### 方式一：使用打包脚本（推荐）

1. **安装 Termux 依赖**（在Termux中执行）：
```bash
pkg update && pkg upgrade
pkg install openjdk-17 wget unzip
```

2. **运行打包脚本**：
```bash
cd /sdcard/Download/TorchlightAuto
chmod +x build.sh
./build.sh
```

3. **安装 APK**：
```bash
# 脚本会自动将APK复制到 /sdcard/Download/火炬助手.apk
# 使用文件管理器安装即可
```

### 方式二：手动构建

1. **安装 Android SDK**：
```bash
mkdir -p $HOME/android-sdk/cmdline-tools
cd $HOME/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip
unzip commandlinetools-linux-10406996_latest.zip
mv cmdline-tools latest
echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
source ~/.bashrc
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

2. **构建项目**：
```bash
cd /sdcard/Download/TorchlightAuto
./gradlew assembleDebug
```

3. **安装 APK**：
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🎮 使用说明

### 第一步：启用无障碍服务
1. 打开「火炬助手」APP
2. 点击提示文字或状态栏，跳转到无障碍设置
3. 找到「火炬助手」服务并启用

### 第二步：启动游戏
- 点击 APP 中的「🚀 启动火炬之光」按钮
- 或手动打开游戏

### 第三步：使用功能

#### 📊 日志监听
- 启动后自动开始监听
- 物品掉落实时显示在日志区域
- 统计数据每秒更新

#### 🎬 脚本录制
1. 输入脚本名称
2. 点击「⏺ 录制」
3. 在游戏中执行你想要录制的操作（点击/滑动）
4. 点击「⏹ 停止」完成录制
5. 脚本自动保存，可在列表中回放

#### 🤖 自动化模式
1. 确保游戏正在运行
2. 点击「▶ 启动」
3. 自动化引擎开始工作：
   - 检测屏幕状态（OCR识别）
   - 自动处理背包满/断线等异常
   - 自动刷副本/回仓库

## ⚙️ 配置说明

### 日志路径
```
/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log
```

### 自动化参数（可在代码中修改）
- `maxRetryCount`: 最大重试次数（默认3）
- `reconnectTimeout`: 重连超时时间（默认30秒）
- `checkInterval`: 状态检测间隔（默认2秒）

## 🛠️ 技术架构

```
├── log/           # 日志监听模块
│   └── LogMonitor.kt
├── script/        # 脚本管理模块
│   └── ScriptManager.kt
├── service/       # 无障碍服务
│   └── AutoAccessibilityService.kt
├── engine/        # 自动化引擎
│   ├── AutomationEngine.kt
│   └── Models.kt
└── ui/            # 用户界面
    └── MainActivity.kt
```

### 核心技术
- **无障碍服务**：实现点击/滑动/手势操作
- **ML Kit OCR**：屏幕文字识别（中文支持）
- **FileObserver**：实时文件监听
- **协程**：异步任务处理

## ⚠️ 注意事项

1. **权限要求**：
   - 必须启用无障碍服务
   - 需要存储权限读取日志文件
   - Android 14 需要额外授权

2. **兼容性**：
   - 专为《火炬之光无限》S13赛季设计
   - 日志格式可能随游戏更新变化
   - OCR识别依赖屏幕文字，分辨率变化可能影响效果

3. **使用建议**：
   - 首次使用建议先录制脚本测试
   - 自动化模式建议在稳定网络环境下使用
   - 长时间运行建议保持屏幕常亮

## 📝 更新日志

### v1.0 (2024-XX-XX)
- ✅ 基础功能完成
- ✅ 日志监听
- ✅ 脚本录制/回放
- ✅ 自动化引擎
- ✅ 异常处理（背包满/断线重连）

## 📄 许可证

本项目仅供学习交流使用，请勿用于商业用途。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**作者**: Operit AI Assistant  
**版本**: 1.0  
**日期**: 2024
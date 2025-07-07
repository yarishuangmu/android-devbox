#!/bin/bash

# 更新包列表并安装依赖
sudo apt-get update
sudo apt-get install -y wget unzip openjdk-17-jdk

# 设置安卓 SDK 的安装目录
ANDROID_SDK_ROOT="/usr/lib/android-sdk"
sudo mkdir -p $ANDROID_SDK_ROOT
sudo chown -R $(whoami) $ANDROID_SDK_ROOT

# 下载安卓 SDK 命令行工具
cd $ANDROID_SDK_ROOT
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip
rm commandlinetools-linux-*.zip

# 命令行工具的目录结构调整
mkdir -p cmdline-tools/latest
mv cmdline-tools/* cmdline-tools/latest/

# 使用 sdkmanager 接受许可并安装必要的包
# 注意：根据您的项目需求调整 build-tools 和 platforms 版本
yes | $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "Android SDK setup complete."
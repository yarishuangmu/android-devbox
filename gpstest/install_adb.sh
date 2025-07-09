#!/bin/bash

# 定义ADB下载URL
ADB_URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
ADB_DIR="$HOME/platform-tools"

# 安装依赖
install_dependencies() {
    echo "正在安装依赖..."
    sudo apt-get update
    sudo apt-get install -y unzip
}

# 下载ADB
download_adb() {
    echo "正在下载ADB..."
    mkdir -p $ADB_DIR
    wget -O platform-tools.zip $ADB_URL
    unzip platform-tools.zip -d $ADB_DIR
    rm platform-tools.zip
}

# 配置环境变量
configure_adb() {
    echo "正在配置ADB环境变量..."
    echo "export PATH=\$PATH:$ADB_DIR/platform-tools" >> ~/.bashrc
    source ~/.bashrc
}

# 验证安装
verify_installation() {
    echo "正在验证ADB安装..."
    adb version
}

# 主函数
main() {
    install_dependencies
    download_adb
    configure_adb
    verify_installation
}

# 执行主函数
main
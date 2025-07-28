#!/bin/bash

# Yaver Code Generator - Full Build and Test Script
# Bu script sample/out klasörünü siler, build.sh'ı çalıştırır ve test-proxy.sh'ı çalıştırır

set -e # Hata durumunda script'i durdur

echo "🧹 Cleaning sample/out folder..."
rm -rf ~/W/Pairs/External/Proxy/* ~/W/Pairs/External/Proxy/.[!.]* ~/W/Pairs/External/Proxy/..?*

echo "🔨 Building yaver-generator..."
./build.sh

echo "🚀 Running proxy test..."
cd sample
./test-proxy.sh
# ./test-fastendpoints.sh
echo "✅ All tasks completed successfully!"

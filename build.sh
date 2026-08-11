#!/usr/bin/env bash
# Render.com build script
set -e

echo "Installing dependencies..."
pip install -r server/requirements.txt

echo "Build complete."

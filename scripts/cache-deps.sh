#!/bin/bash
# Populate a project-local Maven repository with all dependencies needed
# for a production build. Upload this cache with the source to Cloud Build
# to avoid re-downloading ~1-2 GB of JARs on every deploy.
#
# Run this locally whenever dependencies change (pom.xml updates).

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "Clearing existing cache..."
rm -rf .m2-cache

echo "Building with project-local Maven repo (this may take a few minutes)..."
./mvnw clean package -DskipTests -Pproduction -Dmaven.repo.local=.m2-cache

echo ""
echo "Cache populated successfully."
echo "Cache size: $(du -sh .m2-cache | cut -f1)"
echo ""
echo "The .m2-cache/ directory will be uploaded with your next 'gcloud run deploy --source .'"

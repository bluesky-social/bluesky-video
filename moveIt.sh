#! /bin/sh
yarn prepare && rm -Rf ../social-app/node_modules/@bsky.app/video && cp -R . ../social-app/node_modules/@bsky.app/video

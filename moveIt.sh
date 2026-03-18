#! /bin/sh
yarn prepare && rm -Rf ../social-app/node_modules/@bsky.app/bluesky-video && cp -R . ../social-app/node_modules/@bsky.app/bluesky-video

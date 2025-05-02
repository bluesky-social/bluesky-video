#! /bin/sh
yarn prepare && rm -Rf ../social-app/node_modules/bluesky-video && cp -R . ../social-app/node_modules/bluesky-video

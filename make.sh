#!/bin/sh

#duyub@chanjet.com
#change dir of ndk and google_apis as it is in your mac


export ANDROID_NDK=~/android-ndk-r8c/
export GOOGLE_APIS=/Users/David/android-sdk-macosx/add-ons/addon-google_apis-google-8

scons

#unzip -o -d ./dist/sdk ./dist/mobilesdk-3.2.0-osx.zip 

#cp -R ./dist/sdk/mobilesdk/osx/3.2.0 ~/Library/Application\ Support/Titanium/mobilesdk/osx
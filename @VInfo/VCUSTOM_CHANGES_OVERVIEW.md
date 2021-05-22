Parts:
1) Code changes. (easy to recreate from Git compare/deltas)
2) Add a "build artifact" that allows for creation of a standalone ".jar" file. [couldn't get working]

## Building JAR on Windows

1) Install Windows Subsystem for Linux (WSL). (I did v1)
2) Create Linux VM. (I did Ubuntu)
3) Open a Windows Terminal, and navigate to the repo's root directory.
4) Install Java:
```
sudo apt-get update
sudo apt-get install default-jdk
```
5) Run the build script: `sudo .build/build-installer`
   * The build will fail (no install4j license), but that's fine; the jar files are still created.
6) Built jar: `./game-app/game-headed/build/libs/triplea-game-headed-XXX.jar`
7) To run it, first download and install the official release for the version closest to your build: https://triplea-game.org/download (or [here for older versions](https://github.com/triplea-game/triplea/releases))
8) Go to the install directory, and rename the `bin/triplea-game-headed-XXX.jar` file. (eg. adding `.bak` to the end)
9) Copy the jar file from your build to the official install's `bin` folder, renaming it to match the filename you just removed/replaced.
10) Launch your custom version by running the `TripleA.exe` file in the parent folder!
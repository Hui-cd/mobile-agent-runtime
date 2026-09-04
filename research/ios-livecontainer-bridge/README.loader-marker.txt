Mobile Agent research global tweak folder marker.

LiveContainer 3.8.0 TweakLoader skips global loading when this folder contains
only one entry. The standalone host does not create its usual TweakLoader.dylib
symlink until guest launch, so this inert non-dylib marker keeps the entry count
above that guard during the one-time host self-load bootstrap.

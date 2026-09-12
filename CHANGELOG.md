# Changelog

## [2.3.0](https://github.com/giovanniandreuzza/Nimbus/compare/v2.2.0...v2.3.0) (2026-09-12)


### Features

* content digest, computed in the pass that already writes the bytes ([6aa1d1b](https://github.com/giovanniandreuzza/Nimbus/commit/6aa1d1bc864dd8cf0a9399d33fab36cc296aae77))
* tell a full volume apart from broken storage ([8da23ac](https://github.com/giovanniandreuzza/Nimbus/commit/8da23ac9e68a2aca4f140c3e667f828d108c9b95))


### Bug Fixes

* a body that stops short is a dropped link, not a finished transfer ([938efc3](https://github.com/giovanniandreuzza/Nimbus/commit/938efc3170f2ea34f6ae8bd0fe4f87b3dba7c0ab))
* cover the rest of the transport, and stop retries throwing away the partial ([8df0a28](https://github.com/giovanniandreuzza/Nimbus/commit/8df0a28e080fa55ec2a665919d04936609569117))
* emit task state changes from observeAllDownloads, off the hot path ([c37c869](https://github.com/giovanniandreuzza/Nimbus/commit/c37c86983bad3fd417517df618ff6649c44bb21a))
* keep the shortage legible after a restart and past the final flush ([733da20](https://github.com/giovanniandreuzza/Nimbus/commit/733da20e382571025cf94246db47dcad814a0470))
* let release-please tag plainly and pass its own check ([c1685f4](https://github.com/giovanniandreuzza/Nimbus/commit/c1685f4d6f6d94575f9d51375d658ccdb9cd6477))
* re-read the resume offset from the file, not from a variable ([84a535c](https://github.com/giovanniandreuzza/Nimbus/commit/84a535c3f492a0b9ec600e9f2cea5a2c93d7cd0c))
* refuse an unreadable Content-Range and prefer an interrupted write ([681c2f2](https://github.com/giovanniandreuzza/Nimbus/commit/681c2f28d26b52a1870450185cac4d265139ab62))
* retry the transport timing out instead of failing on it ([ec48860](https://github.com/giovanniandreuzza/Nimbus/commit/ec4886016e0ef57c8c863281cc54615db4bf8492))
* stop reporting unclassifiable storage failures as permission denials ([10d2fa1](https://github.com/giovanniandreuzza/Nimbus/commit/10d2fa1124d40d50216469dee3843f297d6bf49d))
* verify a file that is already on disk before calling it finished ([58ed782](https://github.com/giovanniandreuzza/Nimbus/commit/58ed7828ad7300edc4fdab9d084094bf497c8553))


### Performance

* commit the store once per burst instead of once per state change ([896ad0c](https://github.com/giovanniandreuzza/Nimbus/commit/896ad0cdc1ad294833fcc125a198a16b357a87af))


### Internal

* give core its own storage port ([7fe7958](https://github.com/giovanniandreuzza/Nimbus/commit/7fe7958b117c5b36fa552d934520a2a239fe9935))
* settle the transport-vs-storage ambiguity once, in the library ([436d4da](https://github.com/giovanniandreuzza/Nimbus/commit/436d4da071997399957b12e08adf8dba1a0cb1ab))


### Documentation

* bring the written record up to what 2.3.0 actually shipped ([82b8143](https://github.com/giovanniandreuzza/Nimbus/commit/82b81433bf13a0715d60770b381f537ad9d3c7f1))
* design and plan for release automation ([9175e63](https://github.com/giovanniandreuzza/Nimbus/commit/9175e636c4f169f79cac9c68e9750e2ab6818088))
* let release-please own the version in the README ([d12b3e4](https://github.com/giovanniandreuzza/Nimbus/commit/d12b3e4a88ad4d9d058b2a3a26fc3f6c28fa22eb))

## Changelog

<!--
  Generated from Conventional Commits by release-please. Do not edit by hand.
  Entries start at 2.3.0; releases up to and including 2.2.0 predate this file
  and are in the git history and on Maven Central.
-->

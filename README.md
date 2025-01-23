# Nimbus

> [!TIP]
> Want to chat live with me? Join me on [Discord server](https://discord.gg/EBXRXPRD).

Nimbus is a Kotlin Multiplatform library that makes it quick and easy to download any file in your
app, whether it's a large image, a video, or a PDF.
It exposes low-level APIs to manage downloads with the provider you love the most and to store the
downloaded files in the location you prefer.

You heard it right! Nimbus is designed to be easily integrated into Android, iOS, and KMM.
It allows you to download files via Internet, via Bluetooth, or from any other source you can think.
Store the file in the location you prefer, whether it's in the app's cache, in the app's files, or
even in a remote location.

Get started with
our [📚 installation guide](#installation)
and [example project](#example),


Table of contents
=================

<!--ts-->

* [Features](#features)
* [Releases](#releases)
* [Installation](#installation)
* [Example](#example)

<!--te-->

## Features

**Nimbus APIs**: The library provides an interface to manage download operations.

**Concurrency Limit**: Set a custom concurrency limit to control the number of downloads that can
occur simultaneously.

**Dispatcher**: Use custom coroutine dispatchers to control where the download operations run.

**Progress Tracking**: Track the progress of each download operation.

**Pause**: Pause a download operation.

**Resume**: Resume a paused download operation.

**Cancel**: Cancel a download operation.

**Repository Integration**: Easily integrate with repositories for download and file management.

## Releases

* The [changelog](CHANGELOG.md) provides a summary of changes in each release.

## Installation

Add `nimbus` to your `build.gradle` dependencies.

```
dependencies {
    implementation 'io.github.giovanniandreuzza:nimbus:1.0.0'
}
```

### Example

The [SampleAndroid Project](https://github.com/giovanniandreuzza/nimbus/tree/master/sample_android)
demonstrates how to integrate and use Nimbus in an Android project.
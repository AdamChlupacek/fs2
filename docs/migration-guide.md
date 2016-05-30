Upgrading from 0.8 or earlier? A lot has changed, and this document is intended to make the process easier. If you notice anything missing, submit a PR.

## Overview of changes

* Library now has zero third-party dependencies
* Chunking now baked into the library along with support for working with unboxed chunks of primitives; most library operations try to preserve chunkiness whenever possible
* Library no longer reliant on `Task` and users can bring their own effect types
* Much more expressive stream transformation primitives, including support for pushback, prefetching, and arbitrary use of asynchronous steps
* The async package has been generalized to work with any effect type with an [`Async` instance][async]. Added [`Semaphore`](https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.0-M1/fs2-core_2.11-0.9.0-M1-javadoc.jar/!/index.html#fs2.async.mutable.Semaphore), an asynchronous semaphore, used as a concurrency primitive in various places.
* New functionality in [`async.channel`][channel] for forking a stream and sending output through two branches. Used to implement `observe` and `observeAsync` and some experimental combinators (`channel.join`).
* Library now implemented atop a small set of core primitives; there is only one stream interpreter, about 45 LOC, which does not use casts, rest of library could be implemented in 'userspace'
* Various resource safety corner cases have all been addressed and tested, in particular, resource cleanup works in all cases of asynchronous allocation

[channel]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.0-M1/fs2-core_2.11-0.9.0-M1-javadoc.jar/!/index.html#fs2.async.channel$

## Big stuff

Stateful transformations like `take` and so/on are defined in a completely different way, using the [`Pull` data type][pull]. See [this section of the guide](guide.md#statefully-transforming-streams) for details.

[pull]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.0-M1/fs2-core_2.11-0.9.0-M1-javadoc.jar/!/index.html#fs2.Pull
[async]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.0-M1/fs2-core_2.11-0.9.0-M1-javadoc.jar/!/index.html#fs2.Async

All resources should be acquired using `bracket`. Placing finalizers / cleanup actions in an `onComplete` will not guarantee they are run if a stream is being consumed asynchronously or is terminated early by its consumer.

## Small stuff

* `Process` has been renamed to `Stream`.
* There's no `Process1`, `Tee`, `Wye`, or `Channel` type aliases. We decided to simplify. Instead, we have just:
  * `type Pipe[F,A,B] = Stream[F,A] => Stream[F,B]`
  * `type Pipe2[F,A,B,C] = (Stream[F,A], Stream[F,B]) => Stream[F,C]`
  * `Pipe` covers what `Channel` and `Process1` could do before
  * `Pipe2` covers what `Tee` and `Wye`
  * [see the code for the package object](../core/src/main/scala/fs2/fs2.scala)
* Following this renaming, any functions in `process1` have been moved to the module [`pipe`](../core/src/main/scala/fs2/pipe.scala), and `tee` and `wye` have both been combined into [`pipe2`](../core/src/main/scala/fs2/pipe2.scala).
* `mergeN` is now `concurrent.join`
* New functions `pipe.unNoneTerminate` and `Stream.noneTerminate`
* New functions `pipe2.mergeDrainL`, `pipe2.mergeDrainR`
* For transforming a stream, instead of `pipe`, `tee`, `wye`, and `through` methods on `Stream`, there is now just `through` and `through2`:
  * Example - Before: `s.pipe(process1.take(10))` After: `s.through(pipe.take(10))`
  * Example - Before: `s.wye(s2)(wye.blah)` After `s.through2(s2)(pipe2.blah)`
  * TODO: explanation of `throughv` and `through2v` if needed
* Use `t.onFinalize(eff)` instead of `t.onComplete(Stream.eval_(eff))`

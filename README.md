# yetibot.core [![Build Status](https://travis-ci.org/devth/yetibot.core.svg?branch=master)](https://travis-ci.org/devth/yetibot.core)

> NB: The build is not passing because of a [parsing
> bug](https://github.com/devth/yetibot/issues/423) having to do with nested
> parenthesis.

Core yetibot utilities, extracted for shared use among yetibot and its various
plugins. yetibot.core is not meant to be run standalone, but instead used as a
dependency from another project that provides config and optionally other
yetibot plugins, private or public.

The main yetibot commands live at:
https://github.com/devth/yetibot

[<img src="http://clojars.org/yetibot.core/latest-version.svg" />](https://clojars.org/yetibot.core)

## Usage

You can depend on this library to build your own yetibot plugins.
Building your own commands is dead simple. Here's an example command that
adds two numbers:

```clojure
(ns mycompany.plugins.commands.add
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(defn add-cmd
  "add <number1> <number2> # Add two numbers"
  [{[_ n1 n2] :match}] (+ (read-string n1) (read-string n2)))

(cmd-hook #"add" ; command prefix
          #"(\d+)\s+(\d+)" add-cmd)
```

See yetibot's own [commands](https://github.com/devth/yetibot/tree/master/src/yetibot/commands)
for more complex and diverse examples.


## Docs

- [Load Order](doc/load_order.md)
- [Command handling pipeline](doc/command_handling_pipeline.md)
- [Yetibot project README](https://github.com/devth/yetibot)

## Change Log

### 0.2.0

- Add support for per-room settings (new data structure in config). Format:

```edn
:irc {:rooms {"#yetibot" {:broadcast? true}
              "#workstuff" {:broadcast? false}
```

The above `:irc` settings would allow yetibot to post Tweets in the #yetibot
channel, but not in the #workstuff channel. Not backwards compatible with old
config


## License

Copyright © 2013–2015 Trevor C. Hartman

Distributed under the Eclipse Public License version 1.0.

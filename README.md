# Ota-tuf

[tuf][1] implementation for over the air updates. This project is part of [ota-community-edition][2].

This project is split into multiple modules:

*  reposerver - Manages tuf metadata for tuf repositories
*  keyserver - Manages key generation and online role signing for tuf roles
*  cli - Command line tools to manage a remote tuf repository. See [cli/README](cli/README.adoc)
*  libtuf/libtuf-server - Dependencies for the other modules

## Running

`reposerver` and `keyserver` should run as part of
[ota-community-edition][2]. See [cli/README](cli/README.adoc) for
information on how to run the CLI tools.

You can then use `sbt keyserver/run` and `sbt reposerver/run`.

## Running tests

You'll need a mariadb instance running with the users configured in
`application.conf`. If you want it quick you can use
`deploy/ci_setup.sh`. This will create a new docker container running
a database with the proper permissions.

To run tests simply run `sbt test`.

To run integration tests you will also need a running instance of
vault, see above.

    sbt it:test

## Continuous Integration

The `deploy` directory includes scripts required for CI jobs.

## License

This code is licensed under the [Mozilla Public License 2.0](LICENSE), a copy of which can be found in this repository. All code is copyright [ATS Advanced Telematic Systems GmbH](https://www.advancedtelematic.com), 2016-2018.

[1]: https://theupdateframework.github.io/
[2]: https://github.com/advancedtelematic/ota-community-edition

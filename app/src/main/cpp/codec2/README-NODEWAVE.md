# Codec2 in NodeWave

The C sources here are from Codec2 1.2.0 (https://github.com/drowe67/codec2, tag `1.2.0`) by David Rowe and
contributors, licensed under the GNU Lesser General Public License 2.1 (see `COPYING`). They are compatible with
NodeWave's GPL-3.0: the library is linked as a separate shared object (`libnodewave_codec2.so`), and you may replace it
by building your own from the upstream sources.

What was taken: the files needed for the speech modes 700C, 1300, 2400 and 3200 (no modems, no FreeDV). The
codebook tables `codebook*.c`, `config.h` and `codec2/version.h` are the files upstream's own CMake build generates
(`cmake -DUNITTEST=OFF` then `make codec2`); the other files are copied unchanged. To update: check out a newer tag, build it
that way, and copy the same set of files.

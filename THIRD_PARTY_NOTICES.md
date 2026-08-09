# Third-party notices

## rsync 3.4.4

- Project: rsync
- Upstream: https://rsync.samba.org/
- Source release: https://download.samba.org/pub/rsync/src/rsync-3.4.4.tar.gz
- Release SHA-256: `BD88CF82FA653DA32314FB229136407C5C90F80D1758D8F4B091767877D8FA96`
- Release signing-key fingerprint: `9FEF 112D CE19 A0DC 7E88 2CB8 1BB2 4997 A853 5F6F`
- License: GNU General Public License version 3, with the exception stated in rsync's `COPYING`

The Android arm64 build disables OpenSSL, xxhash, zstd, lz4, iconv, IPv6 and roll-simd,
and uses rsync's included popt and zlib copies. The complete corresponding source used by
this project is kept under `native/rsync/src`; the original signed release archive and
signature are under `native/rsync/vendor`.

The included popt source uses its permissive license. Its notice is available at
`native/rsync/src/popt/COPYING`.

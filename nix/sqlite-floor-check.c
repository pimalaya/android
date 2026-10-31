/*
** Checks SQL text against the SQLite this program is linked with, the
** oldest one the app runs on (flake.nix, sqliteFloor).
**
** Standard input holds NUL-terminated triples, kind, name and text: every
** migration first (kind "migration"), run in order on a fresh in-memory
** database, then every statement (kind "statement"), prepared but never
** stepped, against the schema the migrations left. A text splits into SQL
** statements where sqlite3_complete() says one ends, so a migration goes
** on past a statement that fails and reports every failure.
**
** Standard output starts with "sqlite <version>", then one line per
** failure, "<kind> <name>: <error> [<statement head>]". Exits 0 when every
** text passed, 1 on any failure, 2 on unusable input.
*/

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sqlite3.h"

/* How much of a failing statement a report line quotes. */
#define HEAD 72

/* Reads the whole standard input, NUL-terminated past its end. */
static char *slurp(size_t *len) {
  size_t cap = 1 << 16;
  char *buf = malloc(cap + 1);

  *len = 0;
  while (buf) {
    size_t n = fread(buf + *len, 1, cap - *len, stdin);

    *len += n;
    if (n == 0) break;
    if (*len == cap) {
      cap *= 2;
      buf = realloc(buf, cap + 1);
    }
  }
  if (buf) buf[*len] = 0;
  return buf;
}

/* Prints the start of a statement, past its leading comments, on one line. */
static void print_head(const char *sql) {
  int n = 0;

  for (;;) {
    while (isspace((unsigned char)*sql)) sql++;
    if (sql[0] != '-' || sql[1] != '-') break;
    while (*sql && *sql != '\n') sql++;
  }
  for (; *sql && n < HEAD; sql++, n++) {
    putchar(isspace((unsigned char)*sql) ? ' ' : *sql);
  }
  if (*sql) fputs("...", stdout);
}

/*
** Runs one SQL statement, or only prepares it when step is 0. Answers 1
** on failure, after reporting it.
*/
static int check(sqlite3 *db, const char *kind, const char *name,
                 const char *sql, int step) {
  sqlite3_stmt *stmt = NULL;
  int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, NULL);

  if (rc == SQLITE_OK && stmt && step) {
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
    }
    if (rc == SQLITE_DONE) rc = SQLITE_OK;
  }
  if (rc != SQLITE_OK) {
    printf("%s %s: %s [", kind, name, sqlite3_errmsg(db));
    print_head(sql);
    puts("]");
  }
  sqlite3_finalize(stmt);
  return rc != SQLITE_OK;
}

/* Checks every SQL statement of a text in turn; answers the failures. */
static int check_text(sqlite3 *db, const char *kind, const char *name,
                      const char *text, int step) {
  size_t len = strlen(text);
  char *piece = malloc(len + 1);
  size_t start = 0;
  int failures = 0;

  if (!piece) return 1;
  for (size_t end = 0; end < len; end++) {
    if (text[end] != ';' && end + 1 < len) continue;

    memcpy(piece, text + start, end + 1 - start);
    piece[end + 1 - start] = 0;
    if (end + 1 < len && !sqlite3_complete(piece)) continue;

    /* NOTE: a piece of only blanks and comments prepares to no statement,
    ** and check() then does nothing. */
    failures += check(db, kind, name, piece, step);
    start = end + 1;
  }
  free(piece);
  return failures;
}

int main(void) {
  size_t len;
  char *input = slurp(&len);
  sqlite3 *db;
  int failures = 0;

  if (!input) return 2;
  if (sqlite3_open(":memory:", &db) != SQLITE_OK) return 2;
  printf("sqlite %s\n", sqlite3_libversion());

  for (size_t at = 0; at < len;) {
    const char *kind = input + at;
    const char *name = kind + strlen(kind) + 1;
    const char *text;

    if (name >= input + len) return 2;
    text = name + strlen(name) + 1;
    if (text >= input + len) return 2;
    at = (size_t)(text - input) + strlen(text) + 1;

    if (strcmp(kind, "migration") == 0) {
      failures += check_text(db, kind, name, text, 1);
    } else if (strcmp(kind, "statement") == 0) {
      failures += check_text(db, kind, name, text, 0);
    } else {
      return 2;
    }
  }

  sqlite3_close(db);
  free(input);
  return failures ? 1 : 0;
}

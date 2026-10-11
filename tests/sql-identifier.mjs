import assert from 'node:assert/strict';
import { SQLiteDialect } from 'drizzle-orm/sqlite-core';
import { PgDialect } from 'drizzle-orm/pg-core';

for (const dialect of [new SQLiteDialect(), new PgDialect()]) {
  assert.equal(dialect.escapeName('article"title'), '"article""title"');
}
console.log('SQL identifier escaping: SQLite and PostgreSQL passed (2 assertions)');

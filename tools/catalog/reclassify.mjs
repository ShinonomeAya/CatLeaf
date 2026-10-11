#!/usr/bin/env node
/** Apply curated categories to the saved dispositions without network requests. */
import { readFileSync, writeFileSync } from 'node:fs';
import { classifyGeneratedFeedCategory } from './category-classifier.mjs';
import { applyOverrides } from './overrides.mjs';

const path = new URL('./dispositions.json', import.meta.url);
const data = JSON.parse(readFileSync(path, 'utf8'));
for (const d of data.dispositions) {
  const categoryId = classifyGeneratedFeedCategory(d.name, d.url);
  if (categoryId != null) d.categoryId = categoryId;
}
applyOverrides(data.dispositions);
writeFileSync(path, `${JSON.stringify(data, null, 2)}\n`);

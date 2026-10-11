import { mkdirSync, copyFileSync } from 'node:fs';

mkdirSync('dist', { recursive: true });
copyFileSync('public/_routes.json', 'dist/_routes.json');

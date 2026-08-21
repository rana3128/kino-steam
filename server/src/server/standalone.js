'use strict';

/**
 * Headless entry point. Shared folders live in persisted config, not in the
 * command line, so the server can be started from anywhere:
 *
 *   npm run serve -- add "C:\Users\me\Videos"    register a folder
 *   npm run serve -- list                        show configured folders
 *   npm run serve -- remove "C:\Users\me\Videos" unregister
 *   npm run serve                                serve whatever is configured
 *
 * The Electron app reads and writes the same config file.
 */

const { VideoServer } = require('./httpServer');
const { DiscoveryService } = require('./discovery');
const { lanAddresses } = require('./netInfo');
const { Config } = require('./config');

function printFolders(config) {
  const folders = config.get('folders');
  if (!folders.length) {
    console.log('No shared folders configured. Add one with:');
    console.log('  npm run serve -- add "C:\\path\\to\\videos"');
    return;
  }
  console.log('Shared folders:');
  for (const f of folders) {
    const ok = config.existingFolders().includes(f);
    console.log(`  ${ok ? ' ' : '!'} ${f}${ok ? '' : '   (missing)'}`);
  }
}

async function serve(config) {
  const folders = config.existingFolders();
  if (!folders.length) {
    printFolders(config);
    process.exit(1);
  }

  const server = new VideoServer({ port: config.get('port'), name: config.get('name') });
  server.setRoots(folders);

  const port = await server.listen();
  const discovery = new DiscoveryService({ httpPort: port, name: config.get('name') });
  await discovery.start().catch((err) => console.error('[discovery] disabled:', err.message));

  console.log(`Serving ${folders.length} folder(s), ${server.library.index.size} entries indexed`);
  for (const { name, address } of lanAddresses()) {
    console.log(`  http://${address}:${port}   (${name})`);
  }
  console.log(`  http://localhost:${port}`);
  console.log('\nIf the TV cannot connect, allow this app through Windows Firewall on Private networks.');

  const shutdown = async () => {
    console.log('\nshutting down');
    discovery.stop();
    await server.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

async function main() {
  const config = new Config();
  const [cmd, ...rest] = process.argv.slice(2);

  switch (cmd) {
    case 'add': {
      if (!rest.length) {
        console.error('usage: add <folder> [folder...]');
        process.exit(1);
      }
      const added = config.addFolders(rest);
      console.log(`Added ${added} folder(s).`);
      printFolders(config);
      break;
    }
    case 'remove':
    case 'rm': {
      if (!rest.length) {
        console.error('usage: remove <folder>');
        process.exit(1);
      }
      for (const f of rest) {
        console.log(config.removeFolder(f) ? `Removed ${f}` : `Not configured: ${f}`);
      }
      printFolders(config);
      break;
    }
    case 'list':
    case 'ls':
      console.log(`Config: ${config.file}`);
      printFolders(config);
      break;
    case undefined:
    case 'serve':
      await serve(config);
      break;
    default:
      console.error(`Unknown command: ${cmd}`);
      console.error('Commands: serve (default), add, remove, list');
      process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

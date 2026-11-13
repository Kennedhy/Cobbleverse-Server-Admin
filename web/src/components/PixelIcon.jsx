const palette = {
  '#': '#151b34', w: '#fffdf0', s: '#c8cdd5', r: '#e84619', d: '#a9201d',
  y: '#ffc53b', a: '#f18c15', g: '#53bf5a', e: '#1b8b3d', b: '#4586d5',
  n: '#69748b', t: '#f7c696', h: '#593b30', p: '#e45995', l: '#fff28b',
};

const sprites = {
  ball: [
    '.....######.....', '...##rrrrrr##...', '..#rrrrrrrrrr#..', '.#rwwrrrrrrrrd#.',
    '.#wrrrrrrrrrdd#.', '#rrrrrrrrrrrrdd#', '#rrrrr####rrrdd#', '######www#######',
    '#wwww#wss#wwsss#', '#wwwww###wwssss#', '.#wwwwwwwwssss#.', '.#wwwwwwwsssss#.',
    '..#wwwwssssss#..', '...##ssssss##...', '.....######.....',
  ],
  medal: [
    '.....######.....', '...##yyyyyy##...', '..#yyyyyyyyyy#..', '.#yyyyaaaayyyy#.',
    '.#yyyallllayyy#.', '#yyyallyyyayyy#', '#yyawyyyyyyayyy#', '#yyawyyyyyyayyy#',
    '#yyawyyyyyyayyy#', '#yyyayyyyyyayya#', '.#yyyayyyaayya#.', '.#yyyyaaaayyaa#.',
    '..#yyyyyyaaaa#..', '...##aaaaaa##...', '.....######.....',
  ],
  clock: [
    '.....######.....', '...##wwwwww##...', '..#wwwwwwwwww#..', '.#wwwwwwwwwwww#.',
    '.#wwww#wwwwwww#.', '#wwwww#wwwwwwww#', '#wwwww#wwwwwwww#', '#wwwww#####wwww#',
    '#wwwwwwwwwwwwss#', '#wwwwwwwwwwwwss#', '.#wwwwwwwwwwss#.', '.#wwwwwwwwwsss#.',
    '..#wwwwssssss#..', '...##ssssss##...', '.....######.....',
  ],
  server: [
    '.##############.', '#ssssssssssssss#', '#snnnnnnnnnnnns#', '#sn########gnns#',
    '#snnnnnnnnnwwns#', '#nnnnnnnnnnnnnn#', '.##############.', '.##############.',
    '#ssssssssssssss#', '#snnnnnnnnnnnns#', '#sn########gnns#', '#snnnnnnnnnwwns#',
    '#nnnnnnnnnnnnnn#', '.##############.',
  ],
  trainer: [
    '....########....', '...#CCCCCCCC#...', '..#CCwwwwCCCC#..', '..#CCwwwwCCCC#..',
    '.#CCCCCCCCCCCC#.', '.#ssssssssssss#.', '..#hhtttttth#...', '..#ht#tt#tth#...',
    '..#httttttth#...', '...#ttttttt#....', '....##tt##......', '...#eewwee#.....',
    '..#geewweeg#....', '..#gggwwggg#....', '...########.....',
  ],
  book: [
    '................', '.##....##....##.', '.#y#####y#####y#', '.#ywww#y#wwwyy#',
    '.#ywww#y#wwwyy#', '.#ywsw#y#wswyy#', '.#ywww#y#wwwyy#', '.#ywsw#y#wswyy#',
    '.#ywww#y#wwwyy#', '.#ywww#y#wwwyy#', '.#yyww#y#wwyyy#', '.#ayyy#y#yyyaa#',
    '.##aaa###aaa###', '...###...###...', '................',
  ],
  scroll: [
    '................', '..############..', '..#wwwwwwwwww#..', '..#wwwwwwwwww#..',
    '..#ww######ww#..', '..#wwwwwwwwww#..', '..#ww######ww#..', '..#wwwwwwwwww#..',
    '..#ww####wwww#..', '..#wwwwwwwwww#..', '..#ww######ww#..', '..#wwwwwwwwww#..',
    '..#ssssssssss#..', '..############..', '................',
  ],
  chart: [
    '................', '...........###..', '...........#g#..', '...........#g#..',
    '...........#g#..', '.......###.#g#..', '.......#g#.#g#..', '.......#g#.#g#..',
    '...###.#g#.#g#..', '...#g#.#g#.#g#..', '...#g#.#g#.#g#..', '...#e#.#e#.#e#..',
    '...###.###.###..', '................', '................',
  ],
  monitor: [
    '................', '.##############.', '.#ssssssssssss#.', '.#s##########s#.',
    '.#s#nnnnnnnn#s#.', '.#s#nbbbbbbn#s#.', '.#s#nbbbbbbn#s#.', '.#s#nnnnnnnn#s#.',
    '.#s##########s#.', '.#ssssssssssss#.', '.##############.', '.....#ss#.......',
    '....######......', '...########.....', '................',
  ],
  gift: [
    '................', '....##....##....', '...#yy#..#yy#...', '...#yyy##yyy#...',
    '....#yyyyyy#....', '..############..', '..#yyyyrryyyy#..', '..#yyyyrryyyy#..',
    '..############..', '...#yyyrryyy#...', '...#yyyrryyy#...', '...#yyyrryyy#...',
    '...#aaarraaa#...', '...##########...', '................',
  ],
};

function toPaths(sprite, tone) {
  const paths = new Map();
  for (const [y, row] of sprite.entries()) {
    for (const [x, cell] of [...row].entries()) {
      const color = palette[cell === 'C' ? tone : cell];
      if (color) paths.set(color, (paths.get(color) || '') + `M${x} ${y}h1v1h-1z`);
    }
  }
  return [...paths];
}

const spritePaths = Object.fromEntries(Object.entries(sprites).map(([name, rows]) => [name, toPaths(rows, 'g')]));
const trainerPaths = Object.fromEntries(['g', 'r', 'b', 'p', 'a'].map((tone) => [tone, toPaths(sprites.trainer, tone)]));

export function PixelIcon({ name, size = 32, tone = 'g', muted = false }) {
  const paths = name === 'trainer' ? trainerPaths[tone] || trainerPaths.g : spritePaths[name];
  return <svg className={muted ? 'pixel-icon pixel-icon-muted' : 'pixel-icon'} width={size} height={size}
    viewBox="0 0 16 16" shapeRendering="crispEdges" aria-hidden="true">
    {paths.map(([color, path]) => <path key={color} d={path} fill={color} />)}
  </svg>;
}

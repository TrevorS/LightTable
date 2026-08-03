// The ground the components stand on.
//
// The real stylesheets, from deploy/core, rather than anything written for
// Storybook. A component drawn against a copy of the token sheet is a
// component that agrees with the copy — the whole reason to have a gallery is
// that it disagrees with you when the product would.

import '../deploy/core/css/reset.css';
import '../deploy/core/css/kit.css';
import './preview.css';

/** @type {import('@storybook/html-vite').Preview} */
export default {
    parameters: {
        // Catppuccin Mocha is what ships, so it is what a story shows first.
        backgrounds: {
            options: {
                base: { name: 'base', value: '#1e1e2e' },
                mantle: { name: 'mantle', value: '#181825' },
                crust: { name: 'crust', value: '#11111b' }
            }
        },
        controls: { matchers: { color: /(background|color)$/i } }
    },
    initialGlobals: { backgrounds: { value: 'base' } }
};

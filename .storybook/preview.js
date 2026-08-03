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
    initialGlobals: { backgrounds: { value: 'base' }, skin: 'mocha' },

    // The skins are real files under deploy/core/css/skins, and switching is
    // what the editor does: swap the custom properties, redraw nothing. A
    // component that only looks right on one ground is naming a colour
    // somewhere it should be naming a role, and this toolbar is how that shows.
    globalTypes: {
        skin: {
            description: 'Which ground the kit is drawn on',
            toolbar: {
                title: 'Skin',
                icon: 'paintbrush',
                items: [
                    { value: 'mocha', title: 'Catppuccin Mocha (ships)' },
                    { value: 'light', title: 'Light' }
                ],
                dynamicTitle: true
            }
        }
    },

    decorators: [
        (story, context) => {
            document.documentElement.dataset.skin = context.globals.skin ?? 'mocha';
            return story();
        }
    ]
};

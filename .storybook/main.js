// Storybook, pointed at a ClojureScript build.
//
// There is almost nothing here, and that is the design. The components are
// ClojureScript, the stories are ClojureScript, and the only JavaScript in the
// loop is the file-per-component that Storybook's indexer needs — generated
// from the ClojureScript registry by script/gen-stories.mts, not written.
//
// `html-vite` rather than a framework renderer: Replicant renders hiccup into
// a DOM node, and a DOM node is exactly what Storybook's html renderer takes
// back from a story. No adapter, no wrapper component.

/** @type {import('@storybook/html-vite').StorybookConfig} */
export default {
    framework: '@storybook/html-vite',
    stories: ['./stories/**/*.stories.js'],
    addons: [],
    // Storybook phones home with anonymous usage data unless told not to.
    // Nothing else in this repository talks to a third party during a build,
    // and a gallery of buttons is not the place to start.
    core: { disableTelemetry: true },
    // The kit bundle is a build output, and Vite should not try to pre-bundle
    // a megabyte of Closure-compiled ClojureScript into its dep cache.
    viteFinal: (config) => ({
        ...config,
        optimizeDeps: { ...config.optimizeDeps, exclude: ['../generated/kit.js'] }
    })
};

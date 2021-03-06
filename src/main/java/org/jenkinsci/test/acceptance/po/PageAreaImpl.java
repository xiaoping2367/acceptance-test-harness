package org.jenkinsci.test.acceptance.po;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/**
 * Special kind of page object that maps to a portion of a page with multiple INPUT controls.
 * <p/>
 * <p/>
 * Typically we use this to map a set of controls in the configuration page, which is generated
 * by composing various config.jelly files from different extension points.
 *
 * @author Oliver Gondza
 */
public abstract class PageAreaImpl extends CapybaraPortingLayerImpl implements PageArea {
    /**
     * Element path that points to this page area.
     */
    private final String path;

    private final PageObject page;

    protected PageAreaImpl(PageObject context, String path) {
        super(context.injector);
        this.path = path;
        this.page = context;
    }

    protected PageAreaImpl(PageArea area, String relativePath) {
        this(area.getPage(), area.getPath() + "/" + relativePath);

        if (relativePath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Path is supposed to be relative to page area. Given: " + relativePath
            );
        }
    }

    @Override
    public WebElement self() {
        return find(path(""));
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public PageObject getPage() {
        return page;
    }

    /**
     * Returns the "path" selector that finds an element by following the form-element-path plugin.
     * <p/>
     * https://wiki.jenkins-ci.org/display/JENKINS/Form+Element+Path+Plugin
     */
    @Override
    public By path(String rel) {
        if (rel.length() == 0) {
            return by.path(path);
        }

        // this allows path("") and path("/") to both work
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return by.path(path + '/' + rel);
    }

    /**
     * Create a control object that wraps access to the specific INPUT element in this page area.
     * <p/>
     * The {@link Control} object itself can be created early as the actual element resolution happens
     * lazily. This means {@link PageArea} implementations can put these in their fields.
     * <p/>
     * Several paths can be provided to find the first matching element. Useful
     * when element path changed between versions.
     */
    @Override
    public Control control(String... relativePaths) {
        return new Control(this, relativePaths);
    }

    @Override
    public Control control(By selector) {
        return new Control(injector, selector);
    }
}

package com.axellience.vuegwtexamples.client.examples.tree;

import com.axellience.vuegwt.client.jsnative.jstypes.JsArray;

/**
 * @author Adrien Baron
 */
public class Folder
{
    private String name;
    private final JsArray<Folder> content;

    public Folder(String name)
    {
        this.name = name;
        this.content = new JsArray<>();
    }

    public boolean hasContent()
    {
        return content.length > 0;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public JsArray<Folder> getContent()
    {
        return content;
    }
}

package cn.edu.ruc.iir.pixels.core.reader;

import cn.edu.ruc.iir.pixels.core.TypeDescription;

/**
 * pixels
 *
 * @author guodong
 */
public class StringColumnReader
        extends ColumnReader
{
    public StringColumnReader(TypeDescription type)
    {
        super(type);
    }

    @Override
    public void read(byte[] bytes, int offset, int length)
    {
    }
}

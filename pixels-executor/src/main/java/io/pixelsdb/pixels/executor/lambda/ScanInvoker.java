/*
 * Copyright 2022 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.executor.lambda;

import com.alibaba.fastjson.JSON;
import io.pixelsdb.pixels.executor.lambda.output.Output;
import io.pixelsdb.pixels.executor.lambda.output.ScanOutput;

/**
 * The lambda invoker for scan operator.
 *
 * @author hank
 * @date 4/18/22
 */
public class ScanInvoker extends Invoker
{
    protected ScanInvoker(String functionName)
    {
        super(functionName);
    }

    @Override
    protected Output parseOutput(String outputJson)
    {
        return JSON.parseObject(outputJson, ScanOutput.class);
    }
}

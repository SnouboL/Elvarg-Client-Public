package net.runelite.client.plugins.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class GpuFloatBuffer
{
	private static final int INITIAL_CAPACITY = 65536;

	private FloatBuffer buffer = allocateDirect(INITIAL_CAPACITY);

	public void put(float texture, float u, float v, float pad)
	{
		buffer.put(texture).put(u).put(v).put(pad);
	}

	public void put(float[] floats)
	{
		buffer.put(floats);
	}

	public void flip()
	{
		buffer.flip();
	}

	public void clear()
	{
		buffer.clear();
	}

	public boolean isEmpty()
	{
		return buffer.position() == 0;
	}

	public void ensureCapacity(int size)
	{
		int capacity = buffer.capacity();
		final int position = buffer.position();
		if ((capacity - position) < size)
		{
			do
			{
				capacity *= 2;
			}
			while ((capacity - position) < size);

			FloatBuffer newB = allocateDirect(capacity);
			buffer.flip();
			newB.put(buffer);
			buffer = newB;
		}
	}

	public FloatBuffer getBuffer()
	{
		return buffer;
	}

	public static FloatBuffer allocateDirect(int size)
	{
		return ByteBuffer.allocateDirect(size * Float.BYTES)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
	}
}

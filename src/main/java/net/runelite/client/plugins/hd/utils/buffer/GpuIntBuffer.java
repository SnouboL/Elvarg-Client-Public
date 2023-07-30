package net.runelite.client.plugins.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class GpuIntBuffer
{
	private static final int INITIAL_CAPACITY = 65536;

	private IntBuffer buffer = allocateDirect(INITIAL_CAPACITY);

	public void put(int x, int y, int z, int c)
	{
		buffer.put(x).put(y).put(z).put(c);
	}

	public void put(int[] ints) {
		buffer.put(ints);
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

	public GpuIntBuffer ensureCapacity(int size)
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

			IntBuffer newB = allocateDirect(capacity);
			buffer.flip();
			newB.put(buffer);
			buffer = newB;
		}

		return this;
	}

	public IntBuffer getBuffer()
	{
		return buffer;
	}

	public static IntBuffer allocateDirect(int size)
	{
		return ByteBuffer.allocateDirect(size * Integer.BYTES)
				.order(ByteOrder.nativeOrder())
				.asIntBuffer();
	}
}

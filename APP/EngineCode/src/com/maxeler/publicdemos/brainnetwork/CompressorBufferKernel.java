/*********************************************************************
 * Maxeler Technologies: BrainNetwork                                *
 *                                                                   *
 * Version: 1.2                                                      *
 * Date:    05 July 2013                                             *
 *                                                                   *
 * DFE code source file                                              *
 *                                                                   *
 *********************************************************************/

package com.maxeler.publicdemos.brainnetwork;

import java.util.LinkedList;

import com.maxeler.maxcompiler.v2.kernelcompiler.Kernel;
import com.maxeler.maxcompiler.v2.kernelcompiler.KernelParameters;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.Accumulator;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.KernelMath;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.LMemCommandStream;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.Reductions;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.core.Count;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.core.Count.Counter;
import com.maxeler.maxcompiler.v2.kernelcompiler.stdlib.core.Count.WrapMode;
import com.maxeler.maxcompiler.v2.kernelcompiler.types.base.DFEVar;
import com.maxeler.maxcompiler.v2.kernelcompiler.types.composite.DFEStruct;
import com.maxeler.maxcompiler.v2.kernelcompiler.types.composite.DFEStructType;
import com.maxeler.maxcompiler.v2.kernelcompiler.types.composite.DFEVector;
import com.maxeler.maxcompiler.v2.kernelcompiler.types.composite.DFEVectorType;
import com.maxeler.maxcompiler.v2.utils.MathUtils;

// Compressor buffer to deal with multiple pipe with arbitrarily valid inputs

public class CompressorBufferKernel extends Kernel {

	public CompressorBufferKernel(KernelParameters parameters, BrainNetworkParams params, int burst_size_byte) {
		super(parameters);

		//Store time series length, pipelines and burst size
		this.num_pipes = params.getNumPipes();
		this.burst_size_byte = burst_size_byte;

		//Signal when computing loop ends
		DFEVar stop_computing = io.input("stop_computing", dfeBool());

		//Input data (edges) produced by multiple pipes
		DFEVector<DFEStruct> data_input = io.input("data", new DFEVectorType<DFEStruct>(correlationEdgeType,num_pipes), ~stop_computing);

		//Control to signal (tags) produced by multiple pipes
		DFEVector<DFEVar> control_input = io.input("control_signal", new DFEVectorType<DFEVar>(dfeBool(),num_pipes), ~stop_computing);

		//Shift the first m valid edges on the first m lanes (array positions)
		DFEStruct [] shifted_data = createShiftedDataBuffer(data_input,control_input);

		//Keep count of how many valid data in current clock cycle and update buffer indices
		countValidData(control_input,stop_computing);

		//Create a circular buffer to accumulate and send to memory valid edges
		DFEStruct [] circular_buffer = createCircularBuffer(shifted_data);

		//Generate DRAM command when half buffer is full (DRAM data and command streaming)
		manageCircularBuffer(circular_buffer,stop_computing);

		//Stream out the number of active edges when loop ends
		io.scalarOutput("n_active_edges", written_data_after.cast(dfeUInt(64)), dfeUInt(64),stop_computing);

	}

	//Time series length, pipelines and burst size
	private int num_pipes = 0;
	private int burst_size_byte = 0;

	//Define a multipipeline structure for correlation edges
	private final DFEStructType correlationEdgeType = new DFEStructType(
			new DFEStructType.StructFieldType("pixel_a",dfeUInt(32)),
			new DFEStructType.StructFieldType("pixel_b",dfeUInt(32)),
			new DFEStructType.StructFieldType("correlation",dfeFloat(8,24))
	);


	//Given a pipeline with k edges (with m arbitrarily valid), it creates an array with k lanes where the first m are valid edges
	private DFEStruct [] createShiftedDataBuffer(DFEVector<DFEStruct> data_input,DFEVector<DFEVar> control_input){

		//For each data input (but last) create an one-hot mask to use as selector in a multiplexer
		DFEVar [] one_hot_mask = new DFEVar[num_pipes-1];
		for (int i=0; i<num_pipes-1; ++i)
			one_hot_mask[i] = (dfeUInt(num_pipes-i)).newInstance(this);

		//Compose all the one-hot masks
		for (int m=0; m<num_pipes-1; ++m){

			//Initialize current mask with no bits
			DFEVar temp_one_hot_mask = control_input[m];

			//Check if previous masks are all zeros in the same position (meaning that this pipelines was not already selected)
			for (int j=0; j<m; ++j)
				temp_one_hot_mask &= ~one_hot_mask[j].slice(m-j);

			//Each bit in the mask is determined by current control input (one), by previous bits in the current mask (all zeros)
			//and by previous masks at the same position (all zeros)
			for (int i=1; i<num_pipes-m; ++i){

				//Initialize bit with current control input
				DFEVar temp_bit = control_input[i+m];

				//Check if previous bits in the mask are all zeros
				temp_bit &= temp_one_hot_mask.eq(0);

				//Check if previous masks are all zeros in the same position
				for (int j=0; j<m; ++j)
					temp_bit &= ~one_hot_mask[j].slice(m-j+i);

				//Append MSB
				temp_one_hot_mask = temp_bit.cat(temp_one_hot_mask);
			}

			//Copy mask
			one_hot_mask[m] = temp_one_hot_mask.cast(dfeUInt(num_pipes-m));

		}

		//For each data input create a multiplexer in order to shift the data as a contiguous sequence of valid data
		DFEStruct [] shifted_data = new DFEStruct[num_pipes];
		LinkedList<DFEStruct> inputs_list = new LinkedList<DFEStruct>();
		for(int i=0; i<num_pipes; ++i)
			inputs_list.add(data_input[i]);

		//Connect the multiplexer, each time removing one possible input (i.e. input 0 is not used for mux1)
		for(int i=0; i<num_pipes-1; ++i){
			shifted_data[i] = control.oneHotMux(one_hot_mask[i],inputs_list);
			inputs_list.remove(0);
		}

		//Last element does not need a multiplexer
		shifted_data[num_pipes-1] = data_input[num_pipes-1];

		//Limit the fanout to 16 in order to make routing of circular buffer easier
		for(int i=0; i<num_pipes; ++i)
			shifted_data[i] = optimization.limitFanout(shifted_data[i],16);

		return shifted_data;
	}

	//Indices to keep current and future position in the circular
	private DFEVar buffer_index = null;
	private DFEVar new_buffer_index = null;
	private DFEVar wrapping_flag = null;

	//Count total number of written data
	private DFEVar written_data_after = null;

	//Number of edges contained in a memory burst
	private int burst_size = 0;

	//How many burst are necessary to write the result when all pipelines produces valid edges
	private int LCM_bursts = 0;

	//Number of edges contained in n_bursts
	private int LCM_pipes = 0;

	//Count how many edge are valid by reading control input
	private void countValidData(DFEVector<DFEVar> control_input, DFEVar stop_computing){

		//Count the active data by summing boolean control variables (last cycle is zero)
		DFEVar n_active_data = (dfeUInt(MathUtils.bitsToAddress(num_pipes)+1)).newInstance(this);
		n_active_data = control_input[0].cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+1));
		for (int i=1; i<num_pipes; ++i)
			n_active_data += control_input[i].cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+1));
		n_active_data = (~stop_computing) ? n_active_data : constant.var(0);

		//Determine the circular buffer size in bursts (each edge is 12 bytes) and the modulo operator to apply
		burst_size = burst_size_byte/12;

		//Calculate the LCM between burst_size and the number of pipes
		int LCM = (num_pipes * burst_size) / greatestCommonDivisor(num_pipes,burst_size);

		//Express the LCM in bursts
		LCM_bursts = LCM/burst_size;

		//Express the LCM in pipelines
		LCM_pipes = LCM/num_pipes;

		//Keep track of the written data with an accumulator
		Accumulator.Params writtenDataParams = Reductions.accumulator.makeAccumulatorConfig(dfeUInt(32));
		written_data_after = Reductions.accumulator.makeAccumulator(n_active_data.cast(dfeUInt(32)),writtenDataParams);
		DFEVar written_data = written_data_after - n_active_data.cast(dfeUInt(32));

		//Use modulo-k_burst operator to get the index in the buffer
		if (MathUtils.isPowerOf2(num_pipes))
			buffer_index = written_data.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+1));
		else
			buffer_index = KernelMath.modulo(written_data,2*num_pipes);

		//Limit the fanout to 24 in order to make routing of circular buffer easier
		buffer_index = optimization.limitFanout(buffer_index, 24);

		//Check if current writing wraps around the circular buffer
		DFEVar wrapping = buffer_index.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+2));
		wrapping+= n_active_data.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+2));
		wrapping_flag = (wrapping >= 2*num_pipes);

		//Limit the fanout to 24 in order to make routing of circular buffer easier
		wrapping_flag = optimization.limitFanout(wrapping_flag, 24);

		//Calculate the new index after writing the data (still modulo-k_burst operator)
		if (MathUtils.isPowerOf2(num_pipes))
			new_buffer_index = written_data_after.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+1));
		else
			new_buffer_index = KernelMath.modulo(written_data_after,2*num_pipes);

		//Limit the fanout to 24 in order to make routing of circular buffer easier
		new_buffer_index = optimization.limitFanout(new_buffer_index, 24);

	}

	//Greatest common divisor (recursive Euclid's algorithm)
	private int greatestCommonDivisor(int a, int b){

		//Stop recursion
		if (b==0)
			return a;

		//Recursive call
		return greatestCommonDivisor(b,a%b);
	}

	//Create a circular buffer to accumulate valid data
	private DFEStruct [] createCircularBuffer( DFEStruct [] shifted_data){

		//Select the element to write within the circular buffer (within a window determined by index and active data)
		DFEVar [] circular_buffer_control = new DFEVar[2*num_pipes];
		for (int i=0; i<2*num_pipes; ++i)
			circular_buffer_control[i] = control.mux(wrapping_flag,(buffer_index<=i) & (i<new_buffer_index),(buffer_index<=i) | (i<new_buffer_index));

		//For each element in the circular data, select one of the k_burst shifted data input
		DFEVar [] circular_buffer_select = new DFEVar[2*num_pipes];
		for (int i=0; i<2*num_pipes; ++i){

			if (num_pipes>1){

				//Calculate the offset between the current position i and the buffer index
				DFEVar offset = constant.var(dfeUInt(MathUtils.bitsToAddress(num_pipes)+2),i);
				offset -= buffer_index.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)+2));

				//In case of wrapping, the offset is calculated in a different way for the initial elements (less than new_index)
				offset = ((wrapping_flag) & (i<new_buffer_index))
					? (constant.var(dfeUInt(MathUtils.bitsToAddress(num_pipes)+2),2*num_pipes) + offset)
							: offset;

				//Use the calculated offset as selector (cast to k pipeline)
				circular_buffer_select[i] = offset.cast(dfeUInt(MathUtils.bitsToAddress(num_pipes)));
			}
		}

		//Create a list with the shifted data input
		LinkedList<DFEStruct> inputs_list = new LinkedList<DFEStruct>();
		for (int i=0; i<num_pipes; ++i)
			inputs_list.add(shifted_data[i]);

		//Use multiplexers to select each input in the circular buffer
		DFEStruct [] circular_buffer_input = new DFEStruct[2*num_pipes];
		for (int i=0; i<2*num_pipes; ++i)
			if (num_pipes>1){
				circular_buffer_input[i] = control.mux(circular_buffer_select[i],inputs_list);
			}
			else{
				circular_buffer_input[i] = shifted_data[0];
			}

		//Finally, create a circular buffer using 2k_burst stream hold elements
		DFEStruct [] circular_buffer = new DFEStruct[2*num_pipes];
		for (int i=0; i<2*num_pipes; ++i)
			circular_buffer[i] = Reductions.streamHold(circular_buffer_input[i],circular_buffer_control[i]);

		return circular_buffer;
	}

	//Create infrastructure to send a burst to DRAM when half buffer is full
	private void manageCircularBuffer( DFEStruct [] circular_buffer, DFEVar stop_computing){

		//When half buffer is full, send it to the output
		DFEVar ready_to_send = (buffer_index<num_pipes & new_buffer_index>=num_pipes) | (buffer_index>=num_pipes & new_buffer_index<num_pipes);
		Count.Params bufferCounterParams = control.count.makeParams(1)
			.withEnable(ready_to_send);
		Counter bufferCounter = control.count.makeCounter(bufferCounterParams);

		//Group the first half and the second half of the buffer using a multipipe type
		DFEVectorType<DFEStruct> edgeBurstType = new DFEVectorType<DFEStruct>(correlationEdgeType,num_pipes);
		DFEVector<DFEStruct> first_half_buffer = edgeBurstType.newInstance(this);
		DFEVector<DFEStruct> second_half_buffer = edgeBurstType.newInstance(this);
		for(int i=0; i<num_pipes; ++i){
			first_half_buffer[i] <== circular_buffer[i];
			second_half_buffer[i] <== circular_buffer[i+num_pipes];
		}

		//Send the correct half to memory in case half buffer is full or during the last cycle (interrupt)
		DFEVector<DFEStruct> output_half_buffer = bufferCounter.getCount() ? second_half_buffer : first_half_buffer;

		//Counter to keep track of the half buffers sent to memory
		Count.Params halfPipelineCounterParams = control.count.makeParams(MathUtils.bitsToRepresent(LCM_pipes))
			.withEnable(ready_to_send)
			.withMax(LCM_pipes);
		Counter halfPipelineCounter = control.count.makeCounter(halfPipelineCounterParams);

		//Counter to keep track of the bursts accumulated in the output data buffer (up to 16)
		Count.Params burstCounterParams = control.count.makeParams(4)
			.withEnable(halfPipelineCounter.getWrap());
		Counter burstCounter = control.count.makeCounter(burstCounterParams);

		//Counter to keep the offset of the burst written in memory
		Count.Params burstOffsetCounterParams = control.count.makeParams(23)
			.withEnable(burstCounter.getWrap());
		Counter burstOffsetCounter = control.count.makeCounter(burstOffsetCounterParams);

		//Get an offset in bursts where to start writing
		DFEVar fixed_burst_offset = io.scalarInput("burst_offset",dfeUInt(27));

		//Calculate the current offset taking account of buffers with multiple n_bursts
		DFEVar burst_offset = fixed_burst_offset + (burstOffsetCounter.getCount().cast(dfeUInt(27))<<4) * LCM_bursts;

		//Calculate the number of burst to write
		DFEVar bursts_to_write = (stop_computing) ? (burstCounter.getCount().cast(dfeUInt(8))+1) : constant.var(dfeUInt(8),16);
		bursts_to_write *= LCM_bursts;

		//Manage last clock cycles in order to fill a LCM of memory burst
		Count.Params lastClockCyclesCounterParams = control.count.makeParams(MathUtils.bitsToRepresent(LCM_pipes))
			.withEnable(stop_computing)
			.withMax(LCM_pipes)
			.withWrapMode(WrapMode.STOP_AT_MAX);
		Counter lastClockCyclesCounter = control.count.makeCounter(lastClockCyclesCounterParams);

		//Check if we need to send further half buffers to DRAM in order to complete the burst
		DFEVar burst_completed = ((LCM_pipes - halfPipelineCounter.getCount()) < lastClockCyclesCounter.getCount());

		//Last memory command
		DFEVar last_command = lastClockCyclesCounter.getCount().eq(LCM_pipes-1);

		//Stream out the burst to memory
		LMemCommandStream.makeKernelOutput("burst_to_memory_cmd", burstCounter.getWrap() | (last_command & stop_computing),
				burst_offset.cast(dfeUInt(27)), 			//Address in bursts where to start read/write
				bursts_to_write.cast(dfeUInt(8)),		//Number of bursts to read/write (at least 1)
				constant.var(dfeUInt(8),1),				//Offset/stride(2^9) where to skip after read/write (at least 1)
				constant.var(dfeUInt(4),0),				//Select current stream in the command generator group
				stop_computing							//In order to raise an interrupt
				);
		io.output("burst_to_memory", output_half_buffer, edgeBurstType, ready_to_send | (~burst_completed & stop_computing));

	}

}



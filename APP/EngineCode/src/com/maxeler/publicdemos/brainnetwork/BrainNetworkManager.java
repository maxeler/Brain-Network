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

import com.maxeler.maxcompiler.v2.kernelcompiler.KernelConfiguration;
import com.maxeler.maxcompiler.v2.managers.DFEArchitecture;
import com.maxeler.maxcompiler.v2.managers.custom.CustomManager;
import com.maxeler.maxcompiler.v2.managers.custom.DFELink;
import com.maxeler.maxcompiler.v2.managers.custom.blocks.KernelBlock;
import com.maxeler.maxcompiler.v2.managers.custom.stdlib.MemoryControlGroup;
import com.maxeler.maxcompiler.v2.managers.custom.stdlib.MemoryControllerConfig;

public class BrainNetworkManager extends CustomManager {

	public BrainNetworkManager(BrainNetworkParams params) {
		super(params);

		//Define the size of FIFO to 360 (5x72) bits instead of 720 (10x72)..
		// this configuration should make the memory controller easy to route
		MemoryControllerConfig mem_cfg = new MemoryControllerConfig();
		mem_cfg.setDataFIFOPrimitiveWidth(360);
		if (params.enableECC())
			mem_cfg.setEnableParityMode(true, true, 72, false);
		config.setMemoryControllerConfig(mem_cfg);
		config.setAllowNonMultipleTransitions(true);

		//Get the burst size from board type
		int burst_size_byte = 0;
		if (params.getDFEModel().getDFEArchitecture() == DFEArchitecture.MAX2)
			burst_size_byte = 96;
		else
			burst_size_byte = 384;          // = correct size for Vectis, Maia and Coria

		//Connection from host to DRAM in order to directly write points
		MemoryControlGroup write_controller = addMemoryControlGroup("write_controller", MemoryControlGroup.MemoryAccessPattern.LINEAR_1D);
		DFELink host_to_dram = addStreamToOnCardMemory("host_to_dram",write_controller);
		DFELink from_host = addStreamFromCPU("from_host");
		host_to_dram <== from_host;

		//Create a linear memory controller for reading row bursts
		MemoryControlGroup row_memory_controller = addMemoryControlGroup("row_memory_controller",MemoryControlGroup.MemoryAccessPattern.LINEAR_1D);
		DFELink row_bursts_from_DRAM = addStreamFromOnCardMemory("row_bursts_from_DRAM",row_memory_controller);

		//Create a kernel for generating command to read column bursts and connect it to a memory controller
		KernelBlock column_memory_controller = addKernel(new ColumnMemoryControllerKernel(makeKernelParameters("ColumnMemoryControllerKernel"),
				params,burst_size_byte));
		MemoryControlGroup external_column_memory_controller =
			addMemoryControlGroup("custom_column_memory_controller",column_memory_controller.getOutput("column_bursts_from_DRAM_cmd"));
		DFELink column_bursts_from_DRAM = addStreamFromOnCardMemory("column_bursts_from_DRAM",external_column_memory_controller);

		//Replicate CE resources according to the number of pipes in order to improve routing of remaining kernels
		KernelConfiguration config = getCurrentKernelConfig();
		config.optimization.setCEReplicationNumPartitions(params.getCENumPartitions());

		//Create a kernel for calculating correlation and connect it to DRAM
		KernelBlock correlation = addKernel(new LinearCorrelationKernel(makeKernelParameters("LinearCorrelationKernel"),
				params,burst_size_byte));
		correlation.getInput("row_bursts_from_DRAM") <== row_bursts_from_DRAM;
		correlation.getInput("column_bursts_from_DRAM") <== column_bursts_from_DRAM;

		//Create a compressor buffer kernel to select valid edges and store them to DRAM
		KernelBlock compressor_buffer = addKernel(new CompressorBufferKernel(makeKernelParameters("CompressorBufferKernel"),
				params,burst_size_byte));
		compressor_buffer.getInput("stop_computing") <== correlation.getOutput("stop_computing");
		compressor_buffer.getInput("data") <== correlation.getOutput("correlation_edges");
		compressor_buffer.getInput("control_signal") <== correlation.getOutput("control_signal");

		//Connect the compressor buffer to a memory controller
		MemoryControlGroup custom_memory_controller = addMemoryControlGroup("memory_controller",
				compressor_buffer.getOutput("burst_to_memory_cmd"));
		DFELink burst_to_memory = addStreamToOnCardMemory("burst_to_memory",custom_memory_controller);
		burst_to_memory <== compressor_buffer.getOutput("burst_to_memory");

		//Connection from DRAM to host in order to directly read edges
		MemoryControlGroup read_controller = addMemoryControlGroup("read_controller", MemoryControlGroup.MemoryAccessPattern.LINEAR_1D);
		DFELink dram_to_host = addStreamFromOnCardMemory("dram_to_host",read_controller);
		DFELink to_host = addStreamToCPU("to_host");
		to_host <== dram_to_host;

	}
}
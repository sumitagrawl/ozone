/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.shell.s3;

import org.apache.hadoop.hdds.cli.GenericParentCommand;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.cli.MissingSubcommandException;
import org.apache.hadoop.hdds.cli.SubcommandWithParent;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.shell.OzoneShell;
import org.apache.hadoop.ozone.shell.Shell;
import org.kohsuke.MetaInfServices;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Subcommand to group tenant related operations. TODO: see VolumeCommands
 */
@CommandLine.Command(name = "tenant",
    description = "Tenant management",
    subcommands = {
        TenantCreateHandler.class,
        TenantModifyHandler.class,
        TenantDeleteHandler.class
    },
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
@MetaInfServices(SubcommandWithParent.class)
public class TenantCommands implements GenericParentCommand, Callable<Void>,
    SubcommandWithParent {

  @CommandLine.ParentCommand
  private Shell shell;

  @Override
  public Void call() throws Exception {
    throw new MissingSubcommandException(
        this.shell.getCmd().getSubcommands().get("tenant"));
  }

  @Override
  public boolean isVerbose() {
    return shell.isVerbose();
  }

  @Override
  public OzoneConfiguration createOzoneConfiguration() {
    return shell.createOzoneConfiguration();
  }

  @Override
  public Class<?> getParentType() {
    return OzoneShell.class;
  }
}

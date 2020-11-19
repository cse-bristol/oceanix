# This is a supporting file for the oceanix command. Its functions are:
# 1. to build a network description file, which is a json that contains
#    information oceanix uses to provision and deploy machines
# 2. to build disk images to upload to digitalocean as starting points
{
  network-file,                     # this is the path to the network file
  create-image ? null,              #  if set, name of a machine in the
                                    #  network; causes output to be a
                                    #  disk image rather than a network
                                    #  file
  pkgs ? (import <nixpkgs> {}),     # the nixpkgs to use
  hosts ? "{}"                      # map from existing hostname to ip
                                    #  address, used to construct hosts
                                    #  file
}:
let
  lib = pkgs.lib;
  
  selectAttrs = set : list : # selectAttrs {x = 1; y = 2;} ["x"] => {x = 1;}
  builtins.intersectAttrs
  (builtins.listToAttrs (map (x:{name=x; value=true;}) list))
  set;
  
  network = import network-file;
  
  defaults = {
    region = "lon1";
    size = "s-1vcpu-2gb";
    copies = 1;
    ssh-key = "";
    host = false; # whether to include in hostsfile
  } // (network.network.defaults or {});

  machines = # add defaults to machines
    builtins.mapAttrs
    (name: value: (defaults // value))
    (builtins.removeAttrs network ["network"]);

  # cook up hosts file, by looking in the hosts argument for all machines where host = true
  extraHosts =
    let
      hostsMap = builtins.fromJSON hosts;
      hostMachines = lib.mapAttrsToList
           (n: v: {host = n; copies = if v.host then v.copies else 0; })
           machines;
      # so this is now [{host = h; copies = n} ...]
      hostIp = host : hostsMap."${host}" or (builtins.trace "WARNING: Missing host ${host} - provision before deploying" "127.0.0.1");
      hostLine = host : "${hostIp host} ${host}";
      hostLines = builtins.concatMap ({host, copies} :
         if copies == 0 then []
         else if copies == 1 then [(hostLine host)]
         else builtins.genList (n : hostLine "${host}-${toString (n+1)}") copies
      ) hostMachines;
    in builtins.concatStringsSep "\n" hostLines;
  
  evalConfig = import "${pkgs.path}/nixos/lib/eval-config.nix";
  machineConfig = machine-name : # a function which evals a machine's
                                 # config to a final form. This calls
                                 # evalConfig with our extra config
                                 # plugged in on top of the user's
                                 # input
  (evalConfig {
    modules =
      let
        machine = machines."${machine-name}";
        ssh-key = machine.ssh-key;
        ssh-module = {...}: if ssh-key == "" then {} else {
          users.users.root.openssh.authorizedKeys.keys = [ssh-key];
        };
        hosts-module = {...}: {
          networking.extraHosts = extraHosts;
        };
      in
      [
        ./digitalocean.nix # required config to make DO work
        machine.module # user config
        ssh-module # maybe root key, if specified
        hosts-module # extra hosts ip address mapping.
      ];
  }).config;

  # system-root is the derivation for the profile - this is what a
  # machine config ultimately produces. Amongst other things it
  # includes a script ./activate which makes the configuration take
  # effect (starts services, creates users, etc etc)
  system-root = machine : (machineConfig machine).system.build.toplevel;

  # this uses machineConfig to build a disk image for a machine.
  buildImage = machine-name :
  (import "${pkgs.path}/nixos/lib/make-disk-image.nix")  {
    pkgs = pkgs;
    lib = pkgs.lib;
    config = machineConfig machine-name;
    format = "qcow2-compressed";
    name = machine-name;
  };

  # this makes the json description of a machine that oceanix uses
  buildMachine = machine-name : 
  let machine = machines."${machine-name}"; in
  {
    name = machine-name;
    value =
      # the deployment info
      (selectAttrs machine ["size" "region" "ssh-key" "image" "copies" "host"]) //
      # plus the system profile to deploy
      { system = system-root machine-name; }; 
  };

  # description for all machines
  result = builtins.listToAttrs (
    map buildMachine (builtins.attrNames machines)
  );
in

# and now the actual action

if create-image != null then buildImage create-image
else
pkgs.writeTextFile
{
  name = "${network.network.name}.json";
  text = builtins.toJSON result;
}

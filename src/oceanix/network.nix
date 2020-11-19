# this is a supporting file for the oceanix command

# it's a function which builds a single json file, which tells oceanix
# what to provision & deploy.

{
  network-file,
  create-image ? null,
  pkgs ? (import <nixpkgs> {}),
  hosts ? "{}"
}:
let
  lib = pkgs.lib;
  
  selectAttrs = set : list : # selectAttrs {x = 1; y = 2;} ["x"] => {x = 1;}
  builtins.intersectAttrs
  (builtins.listToAttrs (map (x:{name=x; value=true;}) list))
  set;

  hostsMap = builtins.fromJSON hosts;
  
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

  # cook up hosts file - since machines can be replicated we have a
  # bit of trickery required here
  extraHosts =
    let
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
                                 # config to a final form
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
        ./digitalocean.nix # required config
        machine.module # user config
        ssh-module # maybe root key
        hosts-module # hostnames
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
  
  buildMachine = machine-name : # this outputs some stuff to go in a
                                # json file
  let machine = machines."${machine-name}"; in
  {
    name = machine-name;
    value =
      (selectAttrs machine # the deployment info
         ["size" "region" "ssh-key" "image" "copies" "host"]) //
      { system = system-root machine-name; }; # and the system profile to deploy
  };
  
  result = builtins.listToAttrs (
    map buildMachine (builtins.attrNames machines)
  );
in
if create-image != null then buildImage create-image
else
pkgs.writeTextFile
{
  name = "${network.network.name}.json";
  text = builtins.toJSON result;
}

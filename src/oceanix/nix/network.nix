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

  # A function you call like evalConfig {modules = [...]}; and you get
  # back a nixos system.
  evalConfig = import "${pkgs.path}/nixos/lib/eval-config.nix";
  
  network = import network-file;
  defaultConfig = network.network.defaults or ({...}:{});

  machines = # evaluate machines
    builtins.mapAttrs
    (name: value: (evalConfig {
      modules = [
        defaultConfig
        value # the user's config
        ./digitalocean.nix # extras to talk to platform
        ./options.nix # option definitions for deployment.*
        ({...}:{ networking.extraHosts = extraHosts; }) # hosts from below
      ];
    }).config)
    (builtins.removeAttrs network ["network"]);

  # cook up hosts file, by looking in the hosts argument for all machines where host = true
  extraHosts =
    let
      hostsMap = builtins.fromJSON hosts;
      hostMachines = lib.mapAttrsToList
           (n: v: {host = n; copies = if v.deployment.addHost then v.deployment.copies else 0; })
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
in

# and now the actual action

if create-image != null
then (
  (import "${pkgs.path}/nixos/lib/make-disk-image.nix") {
    pkgs = pkgs;
    lib = pkgs.lib;
    config = machines."${create-image}";
    format = "qcow2";
    postVM = "${pkgs.bzip2}/bin/bzip2 $diskImage";
    name = create-image;
  }
) else
pkgs.writeTextFile
{
  name = "${network.network.name}.json";
  text = builtins.toJSON (
    builtins.mapAttrs
    (name: config:
        (selectAttrs config.deployment ["sshKey" "copies" "addHost" "keys"]) //
        (selectAttrs config.deployment.digitalOcean ["region" "size" "image"]) //
        {system = config.system.build.toplevel;})
    machines
  );
}
